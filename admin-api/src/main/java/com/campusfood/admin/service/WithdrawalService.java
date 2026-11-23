package com.campusfood.admin.service;

import com.campusfood.domain.BusinessStates.AccountStatus;
import com.campusfood.domain.BusinessStates.AccountType;
import com.campusfood.domain.BusinessStates.WithdrawalReason;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

import org.springframework.http.HttpStatus;
import com.campusfood.admin.dao.WithdrawalDao;
import com.campusfood.admin.dao.FinanceAccountMapper;
import com.campusfood.admin.dao.FinanceDebtMapper;
import com.campusfood.admin.dao.WithdrawalIntentMapper;
import com.campusfood.admin.dao.WithdrawalRequestMapper;
import com.campusfood.admin.dao.WithdrawalTransferMapper;
import com.campusfood.domain.BusinessStates.WithdrawalIntent;
import com.campusfood.domain.BusinessStates.Withdrawal;
import com.campusfood.domain.BusinessStates.WithdrawalTransfer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** 处理提现申请、审核和可信渠道返回的转账结果。 */
@Service
public class WithdrawalService {
    private final WithdrawalDao db;
    private final FinanceAccountMapper accounts;
    private final FinanceDebtMapper debts;
    private final WithdrawalIntentMapper intents;
    private final WithdrawalRequestMapper requests;
    private final WithdrawalTransferMapper transfers;

    public WithdrawalService(WithdrawalDao db, FinanceAccountMapper accounts, FinanceDebtMapper debts,
                             WithdrawalIntentMapper intents, WithdrawalRequestMapper requests,
                             WithdrawalTransferMapper transfers) {
        this.db = db;
        this.accounts = accounts;
        this.debts = debts;
        this.intents = intents;
        this.requests = requests;
        this.transfers = transfers;
    }

    private static String val(Map<?, ?> m, String k) {
        Object x = m.get(k);
        return x == null ? "" : x.toString().trim();
    }

    private static long cents(Map<?, ?> m, String k) {
        try {
            long n = Long.parseLong(val(m, k));
            if (n > 0 && n <= 1_000_000_000_00L) return n;
        } catch (NumberFormatException ignored) {
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "金额须为正整数分");
    }

    @Transactional
    public Map<String, Object> request(Map<?, ?> payload) {
        String account = val(payload, "accountId"), key = val(payload, "requestKey");
        long requested = cents(payload, "amountCents");
        if (!key.matches("[A-Za-z0-9_-]{8,100}") || account.isEmpty() || account.length() > 64)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "无效账户或幂等键");
        // 先占用幂等键；重复请求读取原结果，不重复变更余额。
        int createdIntent = db.insertIntentIfAbsent(key, account, requested, WithdrawalIntent.PROCESSING);
        if (createdIntent == 0) {
            var old = intents.lockIntent(key);
            if (!account.equals(old.get("account_id")) ||
                    requested != ((Number) old.get("requested_cents")).longValue())
                throw new ResponseStatusException(HttpStatus.CONFLICT, "幂等键已用于其他申请");
            return result(old);
        }
        // 在同一事务内锁定账户，防止并发申请超出可用余额。
        List<Map<String, Object>> matches = accounts.lockForWithdrawal(account);
        if (matches.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "账户不存在");
        var a = matches.get(0);
        if (!AccountStatus.ACCOUNT_ACTIVE.is(a.get("status")) || !List.of("MERCHANT", "RIDER").contains(a.get("type")))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "账户不可提现");
        if (AccountType.MERCHANT.is(a.get("type")) && requests.hasInFlight(account)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "已有进行中的提现申请，请先查看记录");
        }
        long available = ((Number) a.get("balance_cents")).longValue(), recovered = 0;
        // 商户申请提现时先按欠款记录顺序扣回，并分别记录双方账本。
        if (AccountType.MERCHANT.is(a.get("type"))) {
            var outstandingDebts = debts.lockOutstandingDebts(account);
            for (var debt : outstandingDebts) {
                long due = ((Number) debt.get("platform_advance_cents")).longValue() -
                        ((Number) debt.get("recovered_cents")).longValue();
                long take = Math.min(available, due);
                if (take <= 0) break;
                String debtId = debt.get("id").toString();
                debts.increaseDebtRecovered(take, debtId);
                accounts.debitAvailable(take, account);
                accounts.creditPlatform(take);
                String reference = reference(key, debtId);
                journal(account, "debt_recover", reference, -take, 0, key, debtId);
                journal("SYSTEM_PLATFORM", "debt_receive", reference, take, 0, key, debtId);
                available -= take;
                recovered += take;
            }
        }
        // 抵扣后余额不足仍保留本次已扣回的结果，并返回剩余可用余额。
        if (requested > available) {
            intents.markInsufficient(recovered, available, key);
            return Map.of("status", WithdrawalIntent.INSUFFICIENT_FUNDS.name(), "recoveredCents", recovered, "availableCents", available);
        }
        String id = "W" + UUID.randomUUID().toString().replace("-", "");
        // 申请受理时立即冻结提现金额，审核拒绝或明确失败时再解冻。
        accounts.freezeAvailable(requested, requested, account);
        journal(account, "withdraw_freeze", id, -requested, requested);
        String created = LocalDateTime.now(ZoneId.of("Asia/Shanghai")).format(
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        db.insertWithdrawal(id, a.get("name"), a.get("type"), requested, Withdrawal.PENDING_REVIEW, created, account, key);
        intents.markAccepted(recovered, available - requested, id, key);
        return Map.of("status", WithdrawalIntent.ACCEPTED.name(), "withdrawalId", id, "recoveredCents", recovered, "availableCents",
                available - requested);
    }

    private Map<String, Object> result(Map<String, Object> old) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("status", WithdrawalIntent.valueOf(old.get("status").toString()).name());
        r.put("withdrawalId", old.get("withdrawal_id"));
        r.put("recoveredCents", old.get("recovered_cents"));
        r.put("availableCents", old.get("available_after"));
        return r;
    }

    @Transactional
    public Map<String, Object> review(Map<?, ?> payload, String actor) {
        String id = val(payload, "id"), decision = val(payload, "decision"), reason = val(payload, "reason");
        if (!List.of("approve", "reject").contains(decision) || (decision.equals("reject") && reason.isEmpty()) ||
                reason.length() > 500) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "审核决定或原因无效");
        var rows = requests.lockWithdrawal(id);
        if (rows.isEmpty() || !Withdrawal.PENDING_REVIEW.name().equals(rows.get(0).get("status")))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "申请不存在或已处理");
        // 申请行锁已持有，即使有人手工重置状态也不能再次决策或解冻。
        if (db.hasReviewHistory(id) || !transfers.findTransfer(id).isEmpty())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该申请已有审核或转账记录，请先核对原记录");
        var row = rows.get(0);
        String account = Objects.toString(row.get("account_id"), "");
        long amount = ((Number) row.get("amount_cents")).longValue();
        if (account.isEmpty())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "历史申请缺少关联账户，需人工核对");
        accounts.lockAccount(account);
        Withdrawal next = decision.equals("reject") ? Withdrawal.REVIEW_REJECTED : Withdrawal.PENDING_PAYOUT;
        if (decision.equals("reject")) {
            int updated = accounts.unfreezeFunds(amount, amount, account, amount);
            if (updated != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "冻结余额不足，需人工核对");
            journal(account, "withdraw_unfreeze", id, amount, -amount);
        }
        requests.updateStatus(next, decision.equals("approve") ? "审核通过，等待渠道发起" : reason, id);
        db.insertReviewAudit(actor, "withdrawal.review", id, Withdrawal.PENDING_REVIEW.name(), next.name(), reason);
        return Map.of("ok", true, "status", next.name());
    }

    @Transactional
    public Map<String, Object> retry(Map<?, ?> payload, String actor) {
        String id = val(payload, "id");
        if (!id.matches("W[0-9a-f]{32}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "提现编号无效");
        var rows = requests.lockWithdrawal(id);
        if (rows.isEmpty())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "提现申请不存在");
        var withdrawal = rows.get(0);
        var attempts = transfers.lockTransfer(id);
        if (attempts.isEmpty())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "缺少原转账记录");
        var attempt = attempts.get(0);
        var status = Withdrawal.valueOf(withdrawal.get("status").toString());
        var transfer = WithdrawalTransfer.valueOf(attempt.get("status").toString());
        boolean hasChannelReference = !Objects.toString(attempt.get("channel_reference"), "").isBlank();
        if ((status == Withdrawal.TRANSFERRING || status == Withdrawal.AWAITING_CONFIRMATION)
                && (transfer == WithdrawalTransfer.PROCESSING || transfer == WithdrawalTransfer.UNKNOWN) && !hasChannelReference
                && WithdrawalReason.WECHAT_FUNDS_INSUFFICIENT.is(withdrawal.get("reason_code"))) {
            if (transfers.queueRetry(id) != 1)
                throw new ResponseStatusException(HttpStatus.CONFLICT, "该笔提现已在重试队列中");
            db.insertReviewAudit(actor, "withdrawal.retry", id, status.name(), status.name(), "原单重试");
            return Map.of("ok", true, "withdrawalId", id);
        }
        if (status != Withdrawal.PAYOUT_FAILED || transfer != WithdrawalTransfer.FAILED || !hasChannelReference)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "当前渠道状态不允许重试打款");
        if (requests.hasPayoutRetry(id))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "已存在关联的重试申请");

        String accountId = Objects.toString(withdrawal.get("account_id"), "");
        var accountRows = accounts.lockForWithdrawal(accountId);
        if (accountRows.isEmpty())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "提现资金账户不存在");
        var account = accountRows.get(0);
        String type = Objects.toString(account.get("type"), "");
        long amount = ((Number) withdrawal.get("amount_cents")).longValue();
        if (!AccountStatus.ACCOUNT_ACTIVE.is(account.get("status")) || !List.of("MERCHANT", "RIDER").contains(type)
                || ((Number) account.get("balance_cents")).longValue() < amount)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "提现账户不可用或可提现余额不足");
        if (AccountType.MERCHANT.is(type) && (requests.hasInFlight(accountId) || !debts.lockOutstandingDebts(accountId).isEmpty()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "请先处理该商户进行中的提现或欠款");
        if (AccountType.MERCHANT.is(type) && amount >= 200_000)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该金额需先核验商户收款人实名");
        if (!db.hasRetryRecipient(id, accountId, type))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "原转账终态或收款身份需核对");

        // 微信已终结的单据保留原流水，使用关联申请和新的渠道单号。
        String retryId = "W" + UUID.randomUUID().toString().replace("-", "");
        String key = "payout-retry-" + id;
        if (accounts.freezeAvailable(amount, amount, accountId) != 1)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "重新冻结申请金额失败");
        journal(accountId, "withdraw_freeze", retryId, -amount, amount);
        db.insertWithdrawal(retryId, account.get("name"), type, amount, Withdrawal.PENDING_PAYOUT,
                Objects.toString(withdrawal.get("created_at")), accountId, key);
        requests.updateStatus(Withdrawal.PENDING_PAYOUT, "原单打款失败，等待渠道重试", retryId);
        db.insertReviewAudit(actor, "withdrawal.retry", id, status.name(), status.name(), "关联重试申请 " + retryId);
        return Map.of("ok", true, "withdrawalId", retryId);
    }

    @Transactional
    public Map<String, Object> prepareTransfer(String id) {
        var rows = requests.lockStatus(id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "提现申请不存在");
        // 重试使用已有转账单号，避免重复创建渠道请求。
        var prior = transfers.findTransfer(id);
        if (!prior.isEmpty()) return Map.of("transferId", prior.get(0).get("id"),
                "status", WithdrawalTransfer.valueOf(prior.get(0).get("status").toString()).name());
        if (!Withdrawal.PENDING_PAYOUT.name().equals(rows.get(0).get("status")))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "提现申请不在待打款状态");
        String transferId = "T" + UUID.randomUUID().toString().replace("-", "");
        db.insertTransfer(transferId, id, WithdrawalTransfer.PROCESSING);
        requests.markTransferring(id);
        return Map.of("transferId", transferId, "status", WithdrawalTransfer.PROCESSING.name());
    }

    @Transactional
    public Map<String, Object> transferResult(String id, Map<?, ?> payload) {
        String outcome = val(payload, "outcome"), channel = val(payload, "channelReference"), reason =
                val(payload, "reason");
        if (!List.of("success", "failure", "unknown").contains(outcome) || channel.length() > 128 ||
                reason.length() > 500 || (!outcome.equals("unknown") && channel.isEmpty()) ||
                (outcome.equals("failure") && reason.isEmpty()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "渠道结果或凭据无效");
        var rows = requests.lockWithdrawal(id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "提现申请不存在");
        var transferRows = transfers.lockTransfer(id);
        if (transferRows.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "未找到原转账记录");
        var tr = transferRows.get(0);
        WithdrawalTransfer previous = WithdrawalTransfer.valueOf(tr.get("status").toString());
        WithdrawalTransfer expected = switch (outcome) {
            case "success" -> WithdrawalTransfer.SUCCEEDED;
            case "failure" -> WithdrawalTransfer.FAILED;
            default -> WithdrawalTransfer.UNKNOWN;
        };
        // 已终结的渠道结果只允许相同凭据的幂等重放。
        if (previous.terminal()) {
            if (previous == expected && channel.equals(Objects.toString(tr.get("channel_reference"), "")))
                return Map.of("ok", true, "status", (previous == WithdrawalTransfer.SUCCEEDED ? Withdrawal.PAID : Withdrawal.PAYOUT_FAILED).name());
            throw new ResponseStatusException(HttpStatus.CONFLICT, "渠道结果已终结，不能覆盖");
        }
        Withdrawal requestStatus = Withdrawal.valueOf(rows.get(0).get("status").toString());
        if (requestStatus != Withdrawal.TRANSFERRING && requestStatus != Withdrawal.AWAITING_CONFIRMATION)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "提现申请不在打款中");
        // 未知结果不代表失败：继续冻结，等待查询原转账单。
        if (outcome.equals("unknown")) {
            transfers.markUnknown(reason, id);
            requests.markUnknown(id);
            return Map.of("ok", true, "status", WithdrawalTransfer.UNKNOWN.name());
        }
        String account = Objects.toString(rows.get(0).get("account_id"), "");
        long amount = ((Number) rows.get(0).get("amount_cents")).longValue();
        if (account.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "缺少账户关联");
        accounts.lockAccount(account);
        int changed = outcome.equals("failure") ? accounts.unfreezeFunds(amount, amount, account, amount) :
                accounts.consumeFrozen(amount, account, amount);
        if (changed != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "冻结余额不足，需人工核对");
        journal(account, outcome.equals("failure") ? "transfer_failed_unfreeze" : "transfer_success", id,
                outcome.equals("failure") ? amount : 0, -amount);
        Withdrawal next = outcome.equals("failure") ? Withdrawal.PAYOUT_FAILED : Withdrawal.PAID;
        try {
            transfers.finalizeTransfer(expected, channel, reason, id);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "渠道凭据已用于其他转账");
        }
        requests.updateStatus(next, reason, id);
        db.insertChannelAudit(id, next.name(), reason);
        return Map.of("ok", true, "status", next.name());
    }

    private void journal(String account, String type, String ref, long available, long frozen) {
        journal(account, type, ref, available, frozen, null, null);
    }

    private void journal(String account, String type, String ref, long available, long frozen, String requestKey,
                         String debtId) {
        db.insertJournal(account, type, ref, available, frozen, requestKey, debtId);
    }

    /** 为申请与欠款的组合生成稳定的账本关联标识。 */
    private static String reference(String key, String debtId) {
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(
                    (key + ":" + debtId).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

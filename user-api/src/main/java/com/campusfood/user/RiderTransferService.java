package com.campusfood.user;

import com.campusfood.domain.BusinessStates.WithdrawalReason;
import com.campusfood.domain.WithdrawalLimits;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
class RiderTransferService {
    private static final Logger log = LoggerFactory.getLogger(RiderTransferService.class);
    private static final String COMMISSION_SCENE = "1005";
    private static final String COMPENSATION_DESCRIPTION = "报酬说明";
    private static final DateTimeFormatter APPLICATION_TIME =
        DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm[:ss]").withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter REPORT_TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm");
    private static final String SCENE_REQUIRED = "审核通过，未发起转账：请配置获批的微信商家转账场景及报备信息";
    private static final String MATERIAL_REQUIRED = "审核通过，未发起转账：请检查骑手 AppID、微信商户号及签名材料";
    private static final String IDENTITY_REQUIRED = "审核通过，未发起转账：骑手微信身份缺失或 AppID 与配置不一致";
    private static final String SUBMIT_BLOCKED = "审核通过，未发起转账：渠道准备失败，请检查签名材料及管理员服务";
    private static final String APPLICATION_TIME_INVALID = "审核通过，未发起转账：提现申请时间无效";
    private static final String MERCHANT_NAME_REQUIRED = "审核通过，未发起转账：该金额需核验收款人实名";
    private static final String OUTCOME_UNKNOWN = "渠道结果待核实，继续查询原转账单";
    private static final String BILL_NOT_FOUND = "微信查无原转账单，资金仍冻结，需核对首次提交结果";
    private static final String SUBMIT_ERROR_PREFIX = "微信转账提交异常：";
    private static final long REAL_NAME_THRESHOLD = 200_000;
    private enum Party {
        RIDER("骑手", "rider_transfer_channel", "rider_identity i ON i.rider_id=w.account_id",
            "i.openid", "r.name"),
        MERCHANT("商户", "merchant_transfer_channel",
            """
            merchant_identity i ON i.merchant_id=w.account_id
                AND i.app_id=COALESCE(w.recipient_app_id,c.app_id)
                AND i.open_id=COALESCE(w.recipient_open_id,c.recipient_open_id)
                AND (w.applicant_identity_id IS NULL OR i.id=w.applicant_identity_id)
            """, "i.open_id", "NULL");

        final String label;
        final String channel;
        final String identity;
        final String openId;
        final String name;

        Party(String label, String channel, String identity, String openId, String name) {
            this.label = label;
            this.channel = channel;
            this.identity = identity;
            this.openId = openId;
            this.name = name;
        }
    }

    private record Settings(String scene, List<Map<String, String>> reports) {
        boolean ready() {
            return !scene.isBlank() && scene.length() <= 36 && !reports.isEmpty();
        }
    }

    private final JdbcTemplate db;
    private final WechatConfigurationService config;
    private final WechatPayClient wechat;
    private final RestClient admin;
    private final ObjectMapper json;
    private final Settings rider;
    private final Settings merchant;

    RiderTransferService(JdbcTemplate db, WechatConfigurationService config, WechatPayClient wechat,
                         ObjectMapper json, @Value("${app.rider-transfer.scene:}") String scene,
                         @Value("${app.rider-transfer.reports:[]}") String reports,
                         @Value("${app.rider-transfer.admin-url:http://localhost:8080}") String adminUrl,
                         @Value("${app.rider-transfer.admin-key:}") String adminKey,
                         @Value("${app.merchant-transfer.scene:}") String merchantScene,
                         @Value("${app.merchant-transfer.reports:[]}") String merchantReports) {
        this.db = db;
        this.config = config;
        this.wechat = wechat;
        this.json = json;
        this.rider = new Settings(scene.trim(), readReports(json, reports));
        this.merchant = new Settings(merchantScene.trim(), readReports(json, merchantReports));
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3)).build());
        factory.setReadTimeout(Duration.ofSeconds(6));
        this.admin = RestClient.builder().baseUrl(adminUrl).requestFactory(factory)
            .defaultHeader("X-Internal-Key", adminKey).build();
    }

    @Scheduled(fixedDelayString = "${app.rider-transfer.scan-ms:30000}", initialDelay = 30000)
    void scan() {
        returnPendingBelowMinimum();
        scan(Party.RIDER, rider);
    }

    @Scheduled(fixedDelayString = "${app.rider-transfer.scan-ms:30000}", initialDelay = 30000)
    void scanMerchant() {
        scan(Party.MERCHANT, merchant);
    }

    private void scan(Party party, Settings settings) {
        boolean forMerchant = party == Party.MERCHANT;
        var current = settings.ready() ? config.currentTransferConfiguration() : null;
        if (current == null) {
            String reason = settings.ready()
                ? MATERIAL_REQUIRED.replace("骑手", party.label)
                : SCENE_REQUIRED + "（" + party.label + "）";
            db.update("""
                UPDATE withdrawal_request SET reason=?,reason_code=NULL
                WHERE applicant_type=? AND status='PENDING_PAYOUT' AND NOT (reason <=> ?)
                """, reason, party.name(), reason);
            if (db.queryForList("SELECT 1 FROM " + party.channel + " LIMIT 1", Integer.class).isEmpty()) return;
        } else {
            String reason = IDENTITY_REQUIRED.replace("骑手", party.label);
            db.update("""
                UPDATE withdrawal_request w
                LEFT JOIN %s c ON c.withdrawal_id=w.id
                LEFT JOIN %s
                SET w.reason=?,w.reason_code=NULL
                WHERE w.applicant_type=? AND w.status='PENDING_PAYOUT'
                    AND (i.id IS NULL OR %s='' OR i.app_id<>?) AND NOT (w.reason <=> ?)
                """.formatted(party.channel, party.identity, party.openId), reason, party.name(), current.appId(), reason);
            if (forMerchant) {
                db.update("""
                    UPDATE withdrawal_request SET reason=?,reason_code=NULL
                    WHERE applicant_type='MERCHANT' AND status='PENDING_PAYOUT' AND amount_cents>=?
                        AND NOT (reason <=> ?)
                    """, MERCHANT_NAME_REQUIRED, REAL_NAME_THRESHOLD, MERCHANT_NAME_REQUIRED);
            }
        }
        String newOrderScope = forMerchant
            ? "(w.status='PENDING_PAYOUT' AND c.withdrawal_id IS NULL AND t.id IS NULL) OR "
                + "(w.status IN ('PENDING_PAYOUT','TRANSFERRING') AND c.channel_state='PREPARING')"
            : "w.status IN ('PENDING_PAYOUT','TRANSFERRING') AND c.withdrawal_id IS NULL"
                + " AND w.amount_cents>=" + WithdrawalLimits.MINIMUM_CENTS;
        String trackedScope = forMerchant ? """
            AND c.channel_state<>'PREPARING' AND (w.recipient_open_id IS NULL
                OR (w.recipient_app_id=c.app_id AND w.recipient_open_id=c.recipient_open_id))
            """ : "";
        String recipientApp = forMerchant ? "COALESCE(w.recipient_app_id,c.app_id)" : "COALESCE(c.app_id,i.app_id)";
        String recipientOpenId = forMerchant ? "COALESCE(w.recipient_open_id,c.recipient_open_id)"
            : "COALESCE(c.recipient_open_id,i.openid)";
        // 历史单据仅从已记录的渠道收款人恢复，商户绑定关系只用于核验当前身份。
        var orders = db.queryForList("""
            SELECT w.id,w.amount_cents,w.created_at,w.request_key,w.reason,w.reason_code,%s AS name,%s AS app_id,
                   %s AS openid,%s AS current_openid,c.config_version,
                   c.channel_state,c.merchant_no,c.request_payload,COALESCE(t.retry_requested,0) AS retry_requested,
                   t.created_at>CURRENT_TIMESTAMP-INTERVAL 30 DAY AS retry_recent
            FROM withdrawal_request w
            LEFT JOIN %s c ON c.withdrawal_id=w.id
            LEFT JOIN %s
            %s
            LEFT JOIN withdrawal_transfer t ON t.withdrawal_id=w.id
            WHERE w.applicant_type=? AND (
                (w.status IN ('TRANSFERRING','AWAITING_CONFIRMATION') AND c.withdrawal_id IS NOT NULL %s)
                OR ((%s) AND i.app_id=? AND %s<>'' %s))
            AND (c.checked_at IS NULL OR c.checked_at<=?)
            ORDER BY CASE WHEN t.retry_requested THEN 0 ELSE 1 END,
                CASE WHEN w.reason_code IN ('SUBMISSION_BLOCKED','INVALID_APPLICATION_TIME') AND c.checked_at IS NULL THEN 1 ELSE 0 END,
                COALESCE(c.checked_at,0),w.created_at,w.id LIMIT 20
            """.formatted(party.name, recipientApp, recipientOpenId, party.openId,
                party.channel, party.identity, forMerchant ? "" : "LEFT JOIN rider r ON r.id=i.id", trackedScope,
                newOrderScope, party.openId, forMerchant ? "AND w.amount_cents<" + REAL_NAME_THRESHOLD
                    + " AND (c.withdrawal_id IS NULL OR (c.app_id=i.app_id AND c.recipient_open_id=i.open_id))"
                    + " AND EXISTS (SELECT 1 FROM merchant m WHERE m.id=w.account_id AND m.deleted_at IS NULL AND m.audit_status='APPROVED')" : ""),
            party.name(), current == null ? null : current.appId(), System.currentTimeMillis() - 25_000);
        for (var order : orders) {
            String id = order.get("id").toString();
            boolean retry = truthy(order.get("retry_requested"));
            try {
                if (retry && belowRiderMinimum(party, order)) {
                    clearRetry(id);
                    query(party, order, id);
                } else if (retry) {
                    retryOriginal(party, settings, order, id);
                } else if (order.get("config_version") == null || "PREPARING".equals(order.get("channel_state"))) {
                    long version = order.get("config_version") instanceof Number stored ? stored.longValue() : current.version();
                    initiate(party, settings, order, id, version);
                } else {
                    query(party, order, id);
                }
            } catch (DateTimeParseException exception) {
                if (retry) clearRetry(id);
                pendingReason(id, WithdrawalReason.INVALID_APPLICATION_TIME, APPLICATION_TIME_INVALID);
            } catch (RuntimeException exception) {
                // 超时或签名未通过时只查询原单。继续冻结，避免二次付款或误解冻。
                int tracked = db.update("""
                    UPDATE %s SET checked_at=?,
                        channel_state=IF(channel_state IN ('SUBMITTING','WAIT_USER_CONFIRM'),'UNKNOWN',channel_state)
                    WHERE withdrawal_id=?
                    """.formatted(party.channel),
                    System.currentTimeMillis(), id);
                if (exception instanceof WechatPayClient.TransferResponseException response
                        && !retry && (order.get("config_version") == null || "PREPARING".equals(order.get("channel_state")))) {
                    recordSubmissionError(id, response);
                    log.warn("WeChat transfer submission for {} returned {}", id, response.getMessage());
                } else if ("PREPARING".equals(order.get("channel_state"))) {
                    pendingReason(id, WithdrawalReason.SUBMISSION_BLOCKED, SUBMIT_BLOCKED);
                } else if (tracked > 0) {
                    processingReason(id, OUTCOME_UNKNOWN, true);
                } else {
                    int changed = pendingReason(id, WithdrawalReason.SUBMISSION_BLOCKED, SUBMIT_BLOCKED);
                    if (changed > 0) log.warn("Withdrawal transfer preparation failed for {} ({})", id,
                        exception.getClass().getSimpleName());
                }
            }
        }
    }

    private void returnPendingBelowMinimum() {
        var ids = db.queryForList("""
            SELECT w.id FROM withdrawal_request w
            LEFT JOIN rider_transfer_channel c ON c.withdrawal_id=w.id
            LEFT JOIN withdrawal_transfer t ON t.withdrawal_id=w.id
            WHERE w.applicant_type='RIDER' AND w.status='PENDING_PAYOUT'
                AND w.amount_cents>0 AND w.amount_cents<? AND c.withdrawal_id IS NULL AND t.id IS NULL
            ORDER BY w.created_at,w.id LIMIT 20
            """, String.class, WithdrawalLimits.MINIMUM_CENTS);
        for (String id : ids) {
            try {
                returnBelowMinimum(id, Map.of());
            } catch (RuntimeException e) {
                log.warn("Could not return unsubmitted low-value rider withdrawal {} ({})", id, e.getClass().getSimpleName());
            }
        }
    }

    private void initiate(Party party, Settings settings, Map<String, Object> order, String id, long version) {
        boolean forMerchant = party == Party.MERCHANT;
        String appId = order.get("app_id").toString();
        String openId = order.get("openid").toString();
        var credentials = config.transferPayment(version, forMerchant, appId);
        String requestKey = Objects.toString(order.get("request_key"), "");
        if (requestKey.startsWith("payout-retry-") && !Boolean.TRUE.equals(db.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM %s WHERE withdrawal_id=? AND channel_state IN ('FAIL','CANCELLED')
                AND app_id=? AND merchant_no=? AND COALESCE(recipient_open_id,?)=?)
            """.formatted(party.channel), Boolean.class, requestKey.substring("payout-retry-".length()),
                appId, credentials.merchant(), openId, openId))) {
            pendingReason(id, "原转账的终态或收款身份需核对");
            return;
        }
        String bill = billNumber(id);
        var payload = payload(party, settings, order, bill, credentials);
        String snapshot = json.writeValueAsString(payload);
        if (forMerchant && order.get("config_version") == null && db.update("""
            INSERT IGNORE INTO merchant_transfer_channel(withdrawal_id,config_version,app_id,merchant_no,
                recipient_open_id,channel_state,checked_at)
            VALUES(?,?,?,?,?,'PREPARING',?)
            """, id, version, appId, credentials.merchant(), openId, System.currentTimeMillis()) != 1) {
            return;
        }
        admin.post().uri("/internal/v1/withdrawals/{id}/transfer/prepare", id).retrieve().toBodilessEntity();
        // 持久化唯一渠道记录后提交一次。进入 SUBMITTING 后只查原单。
        int claimed = forMerchant
            ? db.update("""
                UPDATE merchant_transfer_channel SET channel_state='SUBMITTING',checked_at=?,request_payload=?
                WHERE withdrawal_id=? AND channel_state='PREPARING'
                """, System.currentTimeMillis(), snapshot, id)
            : db.update("""
                INSERT IGNORE INTO rider_transfer_channel(withdrawal_id,config_version,app_id,merchant_no,checked_at,
                    recipient_open_id,request_payload)
                VALUES(?,?,?,?,?,?,?)
                """, id, version, appId, credentials.merchant(), System.currentTimeMillis(), openId, snapshot);
        if (claimed == 1) {
            apply(party, id, bill, wechat.transfer(credentials, payload));
        }
    }

    private Map<String, Object> payload(Party party, Settings settings, Map<String, Object> order, String bill,
            WechatConfigurationService.PaymentCredentials credentials) {
        boolean forMerchant = party == Party.MERCHANT;
        long amount = ((Number) order.get("amount_cents")).longValue();
        var payload = new LinkedHashMap<String, Object>(Map.of(
            "appid", order.get("app_id"), "out_bill_no", bill, "transfer_scene_id", settings.scene(),
            "openid", order.get("openid"), "transfer_amount", amount,
            "transfer_remark", forMerchant ? "校园订单结算提现" : "校园配送收益",
            "transfer_scene_report_infos", forMerchant ? settings.reports() : reportsFor((String) order.get("created_at"))));
        if (amount >= REAL_NAME_THRESHOLD) {
            payload.put("user_name", wechat.encryptRecipient(credentials, order.get("name").toString()));
        }
        return payload;
    }

    private void retryOriginal(Party party, Settings settings, Map<String, Object> order, String id) {
        var credentials = config.transferPayment(((Number) order.get("config_version")).longValue(),
            party == Party.MERCHANT, order.get("app_id").toString());
        String bill = billNumber(id);
        var result = wechat.queryTransfer(credentials, bill);
        if (!"NOT_FOUND".equals(result.path("code").asText())) {
            validateTransfer(order, credentials, bill, result);
            clearRetry(id);
            apply(party, id, bill, result);
            return;
        }

        Object saved = order.get("request_payload");
        boolean legacy = saved == null;
        if (!credentials.merchant().equals(order.get("merchant_no")) || !credentials.appId().equals(order.get("app_id"))
                || order.get("openid") == null || !order.get("openid").equals(order.get("current_openid"))
                || (legacy && !settings.ready()) || !truthy(order.get("retry_recent"))) {
            clearRetry(id);
            processingReason(id, "原单身份、场景配置或查单时效需核对", false);
            return;
        }
        // 新单复用持久化请求，历史余额不足单在微信查无原单时用原申请参数补齐快照。
        Map<String, Object> payload = legacy ? payload(party, settings, order, bill, credentials)
                : json.readValue(saved.toString(), new TypeReference<Map<String, Object>>() {});
        if (!bill.equals(payload.get("out_bill_no")) || !order.get("app_id").equals(payload.get("appid"))
                || !order.get("openid").equals(payload.get("openid"))
                || !(payload.get("transfer_amount") instanceof Number amount)
                || amount.longValue() != ((Number) order.get("amount_cents")).longValue()) {
            clearRetry(id);
            processingReason(id, "原转账请求快照与提现申请不匹配", false);
            return;
        }
        int claimed = db.update("""
            UPDATE %s c JOIN withdrawal_transfer t ON t.withdrawal_id=c.withdrawal_id
            JOIN withdrawal_request w ON w.id=c.withdrawal_id JOIN finance_account a ON a.id=w.account_id
            SET t.retry_requested=FALSE,c.channel_state='SUBMITTING',c.checked_at=?,c.request_payload=?,
                c.recipient_open_id=COALESCE(c.recipient_open_id,?),w.reason='已发起原单重试，等待微信结果',w.reason_code=NULL
            WHERE w.id=? AND w.status IN ('TRANSFERRING','AWAITING_CONFIRMATION') AND w.reason_code='WECHAT_FUNDS_INSUFFICIENT'
                AND c.channel_state='UNKNOWN' AND c.package_info IS NULL AND t.retry_requested
                AND t.status IN ('PROCESSING','UNKNOWN') AND t.channel_reference IS NULL
                AND a.frozen_cents>=w.amount_cents
                AND (SELECT SUM(j.frozen_delta) FROM account_journal j
                     WHERE j.reference_id=w.id AND j.account_id=w.account_id)=w.amount_cents
            """.formatted(party.channel), System.currentTimeMillis(), json.writeValueAsString(payload),
            order.get("openid"), id);
        if (claimed == 0) {
            db.update("""
                UPDATE withdrawal_transfer t JOIN withdrawal_request w ON w.id=t.withdrawal_id
                SET t.retry_requested=FALSE,w.reason='重试条件已变化，请核对原单及冻结流水',w.reason_code=NULL
                WHERE w.id=? AND w.reason_code='WECHAT_FUNDS_INSUFFICIENT' AND t.retry_requested
                """, id);
            return;
        }
        try {
            apply(party, id, bill, wechat.transfer(credentials, payload));
        } catch (WechatPayClient.TransferResponseException response) {
            db.update("UPDATE " + party.channel + " SET channel_state='UNKNOWN' WHERE withdrawal_id=? AND channel_state='SUBMITTING'", id);
            recordSubmissionError(id, response);
            log.warn("WeChat original transfer retry for {} returned {}", id, response.getMessage());
        }
    }

    private void clearRetry(String id) {
        db.update("UPDATE withdrawal_transfer SET retry_requested=FALSE WHERE withdrawal_id=? AND retry_requested", id);
    }

    private static boolean truthy(Object value) {
        return Boolean.TRUE.equals(value) || value instanceof Number number && number.intValue() != 0;
    }

    /** 申请时间在管理员端已按北京时间入库，生成报备时只去掉秒。 */
    List<Map<String, String>> reportsFor(String createdAt) {
        if (!COMMISSION_SCENE.equals(rider.scene())) return rider.reports();
        String description = LocalDateTime.parse(createdAt == null ? "" : createdAt, APPLICATION_TIME)
            .format(REPORT_TIME) + "配送费提现";
        return rider.reports().stream()
            .map(report -> COMPENSATION_DESCRIPTION.equals(report.get("info_type"))
                ? Map.of("info_type", COMPENSATION_DESCRIPTION, "info_content", description)
                : report)
            .toList();
    }

    private int pendingReason(String id, String reason) {
        return pendingReason(id, null, reason);
    }

    private int pendingReason(String id, WithdrawalReason code, String reason) {
        String reasonCode = code == null ? null : code.name();
        return db.update("""
            UPDATE withdrawal_request SET reason=?,reason_code=?
            WHERE id=? AND status='PENDING_PAYOUT' AND (NOT (reason <=> ?) OR NOT (reason_code <=> ?))
            """, reason, reasonCode, id, reason, reasonCode);
    }

    private void query(Party party, Map<String, Object> order, String id) {
        var credentials = config.transferPayment(((Number) order.get("config_version")).longValue(),
            party == Party.MERCHANT, order.get("app_id").toString());
        String bill = billNumber(id);
        var result = wechat.queryTransfer(credentials, bill);
        if ("NOT_FOUND".equals(result.path("code").asText())) {
            // queryTransfer 接受的是验签成功的 404；历史单超过查单时效或缺少拒绝记录时仍保留冻结。
            if (belowRiderMinimum(party, order) && truthy(order.get("retry_recent"))
                    && "UNKNOWN".equals(order.get("channel_state"))
                    && WithdrawalReason.WECHAT_SUBMISSION_ERROR.is(order.get("reason_code"))
                    && WithdrawalLimits.REJECTED_SUBMISSION_REASON.equals(order.get("reason"))
                    && credentials.merchant().equals(order.get("merchant_no"))) {
                returnBelowMinimum(id, Map.of("appId", credentials.appId(), "merchantNo", credentials.merchant()));
                return;
            }
            db.update("UPDATE " + party.channel + " SET checked_at=?,channel_state='UNKNOWN' WHERE withdrawal_id=?",
                System.currentTimeMillis(), id);
            processingReason(id, BILL_NOT_FOUND, true);
            return;
        }
        validateTransfer(order, credentials, bill, result);
        apply(party, id, bill, result);
    }

    private void returnBelowMinimum(String id, Map<String, String> channel) {
        admin.post().uri("/internal/v1/withdrawals/{id}/transfer/return-below-minimum", id)
            .body(channel).retrieve().toBodilessEntity();
    }

    private static boolean belowRiderMinimum(Party party, Map<String, Object> order) {
        return party == Party.RIDER && order.get("amount_cents") instanceof Number amount
            && amount.longValue() > 0 && amount.longValue() < WithdrawalLimits.MINIMUM_CENTS;
    }

    private void validateTransfer(Map<String, Object> order,
            WechatConfigurationService.PaymentCredentials credentials, String bill, JsonNode result) {
        // 查单的签名、商户、AppID、收款人及金额均须匹配原提现申请。
        if (!bill.equals(result.path("out_bill_no").asText())
                || !credentials.merchant().equals(result.path("mch_id").asText())
                    || !order.get("app_id").equals(result.path("appid").asText())
                    || !order.get("openid").equals(result.path("openid").asText())
                    || ((Number) order.get("amount_cents")).longValue() != result.path("transfer_amount").asLong(-1)) {
            throw WechatConfigurationService.paymentUnavailable();
        }
    }

    private void apply(Party party, String id, String bill, JsonNode result) {
        if (!bill.equals(result.path("out_bill_no").asText())) {
            db.update("UPDATE " + party.channel + " SET checked_at=?,channel_state='UNKNOWN',package_info=NULL WHERE withdrawal_id=?",
                System.currentTimeMillis(), id);
            processingReason(id, OUTCOME_UNKNOWN, true);
            return;
        }
        String state = result.path("state").asText();
        if (!List.of("ACCEPTED", "PROCESSING", "WAIT_USER_CONFIRM", "TRANSFERING",
                "SUCCESS", "FAIL", "CANCELING", "CANCELLED").contains(state)) {
            throw WechatConfigurationService.paymentUnavailable();
        }
        String packet = result.path("package_info").asText();
        if (packet.length() > 2048) {
            throw WechatConfigurationService.paymentUnavailable();
        }
        db.update("""
            UPDATE %s SET channel_state=?,checked_at=?,
                package_info=CASE WHEN ? IN ('SUCCESS','FAIL','CANCELLED') THEN NULL
                    WHEN ?='' THEN package_info ELSE ? END
            WHERE withdrawal_id=?
            """.formatted(party.channel), state, System.currentTimeMillis(), state, packet, packet, id);
        if (List.of("SUCCESS", "FAIL", "CANCELLED").contains(state)) {
            String reference = result.path("transfer_bill_no").asText();
            if (reference.isBlank() || reference.length() > 128) {
                throw WechatConfigurationService.paymentUnavailable();
            }
            String reason = result.path("fail_reason").asText();
            if (reason.isBlank() && !"SUCCESS".equals(state)) {
                reason = "微信转账已关闭或失败";
            }
            admin.post().uri("/internal/v1/withdrawals/{id}/transfer/result", id)
                .body(Map.of("outcome", "SUCCESS".equals(state) ? "success" : "failure",
                    "channelReference", reference, "reason", reason.substring(0, Math.min(500, reason.length()))))
                .retrieve().toBodilessEntity();
        } else {
            processingReason(id, switch (state) {
                case "WAIT_USER_CONFIRM" -> "微信已受理，请" + party.label + "在小程序内确认收款";
                case "CANCELING" -> "微信正在撤销转账，等待最终结果";
                default -> "微信正在处理转账，等待渠道结果";
            }, false);
        }
    }

    private void recordSubmissionError(String id, WechatPayClient.TransferResponseException error) {
        WithdrawalReason code = error.insufficientFunds()
            ? WithdrawalReason.WECHAT_FUNDS_INSUFFICIENT : WithdrawalReason.WECHAT_SUBMISSION_ERROR;
        processingReason(id, code, SUBMIT_ERROR_PREFIX + error.getMessage(), false);
    }

    private void processingReason(String id, String reason, boolean preserveSubmitError) {
        processingReason(id, null, reason, preserveSubmitError);
    }

    private void processingReason(String id, WithdrawalReason code, String reason, boolean preserveSubmitError) {
        String reasonCode = code == null ? null : code.name();
        db.update("""
            UPDATE withdrawal_request SET reason=?,reason_code=?
            WHERE id=? AND status IN ('TRANSFERRING','AWAITING_CONFIRMATION')
                AND (NOT (reason <=> ?) OR NOT (reason_code <=> ?))
                AND (?=FALSE OR reason_code IS NULL OR reason_code NOT IN ('WECHAT_FUNDS_INSUFFICIENT','WECHAT_SUBMISSION_ERROR'))
            """, reason, reasonCode, id, reason, reasonCode, preserveSubmitError);
    }

    private static String billNumber(String id) {
        if (!id.matches("W[0-9a-f]{32}")) {
            throw new IllegalArgumentException("提现申请编号不符合渠道单号规则");
        }
        return id.substring(1);
    }

    private static List<Map<String, String>> readReports(ObjectMapper json, String value) {
        try {
            var reports = json.readValue(value, new TypeReference<List<Map<String, String>>>() {});
            if (reports != null && reports.stream().allMatch(report -> report != null
                    && report.get("info_type") != null && !report.get("info_type").isBlank()
                    && report.get("info_content") != null && !report.get("info_content").isBlank())) {
                return reports;
            }
        } catch (tools.jackson.core.JacksonException ignored) {
            // 报备配置错误只阻塞新转账，已有单据仍须查单。
        }
        log.warn("Withdrawal transfer reports must be a JSON array of scene report fields");
        return List.of();
    }
}

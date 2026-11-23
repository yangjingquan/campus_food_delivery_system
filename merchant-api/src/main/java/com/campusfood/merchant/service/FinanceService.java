package com.campusfood.merchant.service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class FinanceService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private final JdbcTemplate db;

    public FinanceService(JdbcTemplate db) {
        this.db = db;
    }

    public Map<String, Object> summary(String merchant, int days) {
        int period = days == 30 ? 30 : days == 7 ? 7 : 1;
        long from = LocalDate.now(BUSINESS_ZONE).minusDays(period - 1L).atStartOfDay(BUSINESS_ZONE).toInstant().toEpochMilli();
        var sales = db.queryForMap("""
            SELECT COUNT(*) AS paidOrders,COALESCE(SUM(paid_cents),0) AS paidCents,
                   COALESCE(SUM(food_cents),0) AS foodCents,COALESCE(SUM(refunded_cents),0) AS refundedCents,
                   COALESCE(SUM(paid_cents-refunded_cents),0) AS netCents
            FROM business_order WHERE merchant_id=? AND paid_at>=?
            """, merchant, from);
        var account = db.queryForList("""
            SELECT balance_cents AS availableCents,frozen_cents AS frozenCents
            FROM finance_account WHERE id=? AND type='MERCHANT'
            """, merchant).stream().findFirst().orElse(Map.of("availableCents", 0, "frozenCents", 0));
        var debt = db.queryForObject("""
            SELECT COALESCE(SUM(platform_advance_cents-recovered_cents),0)
            FROM finance_debt WHERE merchant_account_id=?
            """, Long.class, merchant);
        return Map.of("sales", sales, "account", account, "debtCents", debt, "merchantId", merchant);
    }

    public List<Map<String, Object>> records(String merchant, String kind) {
        String sql = switch (kind) {
            case "settlements" -> """
                SELECT o.id,o.food_cents AS foodCents,o.food_refunded_cents AS foodRefundedCents,
                       COALESCE(r.food_mode,'PERCENTAGE') AS foodRuleMode,
                       COALESCE(r.food_value,d.food_rate_snapshot*100) AS foodRuleValue,
                       o.food_cents-o.food_refunded_cents AS foodBaseCents,
                       COALESCE(s.food_commission_cents,
                           IF(d.merchant_settled_cents IS NULL,NULL,o.food_cents-o.food_refunded_cents-d.merchant_settled_cents)) AS foodCommissionCents,
                       d.merchant_settled_cents AS settledCents,d.merchant_settled_at AS settledAt,
                       IF(o.refunded_cents>0 AND o.refunded_cents=o.paid_cents,'CLOSED',o.settlement_status) AS status,
                       COALESCE(o.last_refund_at,o.completed_at) AS settlementBaseAt
                FROM business_order o JOIN user_order_detail d ON d.order_id=o.id
                LEFT JOIN order_pricing_rule r ON r.order_id=o.id
                LEFT JOIN order_settlement_snapshot s ON s.order_id=o.id AND s.event_type='settlement_credit'
                WHERE o.merchant_id=? AND o.paid_at>0 ORDER BY o.paid_at DESC LIMIT 100
                """;
            case "debts" -> """
                SELECT id,order_id AS orderId,rider,rider_due_cents AS riderDueCents,
                       platform_advance_cents AS advanceCents,recovered_cents AS recoveredCents,note
                FROM finance_debt WHERE merchant_account_id=? ORDER BY id DESC LIMIT 100
                """;
            case "ledger" -> """
                SELECT id,event_type AS eventType,reference_id AS referenceId,available_delta AS availableDelta,
                       frozen_delta AS frozenDelta,created_at AS createdAt
                FROM account_journal WHERE account_id=? ORDER BY id DESC LIMIT 100
                """;
            case "withdrawals" -> """
                SELECT w.id,w.amount_cents AS amountCents,w.created_at AS createdAt,w.reason,
                       CASE WHEN w.status='TRANSFERRING' AND c.channel_state='WAIT_USER_CONFIRM' THEN 'AWAITING_CONFIRMATION'
                            WHEN w.status='TRANSFERRING' AND c.channel_state='UNKNOWN' THEN 'CONFIRMATION_UNKNOWN'
                            ELSE w.status END AS status,
                       (w.status='TRANSFERRING' AND c.channel_state='WAIT_USER_CONFIRM'
                            AND c.package_info IS NOT NULL AND c.checked_at>=
                                UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000-120000) AS canConfirm
                FROM withdrawal_request w
                LEFT JOIN merchant_transfer_channel c ON c.withdrawal_id=w.id
                WHERE w.account_id=? AND w.applicant_type='MERCHANT' ORDER BY w.created_at DESC,w.id DESC LIMIT 100
                """;
            default -> throw Inputs.invalid("未知财务记录类型");
        };
        return db.queryForList(sql, merchant);
    }

    public Map<String, Object> withdrawalConfirmation(String merchant, long identityId, String withdrawalId) {
        return db.queryForList("""
            SELECT c.app_id AS appId,c.merchant_no AS mchId,c.package_info AS package
            FROM merchant_transfer_channel c
            JOIN withdrawal_request w ON w.id=c.withdrawal_id
            JOIN merchant_identity i ON i.merchant_id=w.account_id
                AND i.app_id=c.app_id AND i.open_id=c.recipient_open_id
            WHERE w.id=? AND w.account_id=? AND i.id=? AND w.applicant_type='MERCHANT'
                AND w.status='TRANSFERRING' AND c.channel_state='WAIT_USER_CONFIRM'
                AND c.package_info IS NOT NULL AND c.checked_at>=?
            """, withdrawalId, merchant, identityId, System.currentTimeMillis() - 120_000L)
            .stream().findFirst().orElseThrow(() -> new ResponseStatusException(
                HttpStatus.CONFLICT, "暂时无法确认收款，请刷新提现记录"));
    }
}

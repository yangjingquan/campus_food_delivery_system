package com.campusfood.rider.service;

import com.campusfood.domain.BusinessStates.Withdrawal;
import com.campusfood.domain.WithdrawalLimits;

import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RiderFinance {
    private static final String WITHDRAWAL_STATUS = """
        CASE WHEN w.status IN ('TRANSFERRING','AWAITING_CONFIRMATION') AND c.channel_state='WAIT_USER_CONFIRM' THEN 'AWAITING_CONFIRMATION'
             WHEN w.status IN ('TRANSFERRING','AWAITING_CONFIRMATION') AND c.channel_state='UNKNOWN' THEN 'CONFIRMATION_UNKNOWN'
             ELSE w.status END
        """.strip();
    private static final String WITHDRAWALS = """
        SELECT w.id,w.amount_cents AS amountCents,%s AS status,w.created_at AS createdAt,w.reason,
               t.status AS transferStatus,t.reason AS transferReason
        FROM withdrawal_request w LEFT JOIN withdrawal_transfer t ON t.withdrawal_id=w.id
        LEFT JOIN rider_transfer_channel c ON c.withdrawal_id=w.id
        WHERE w.account_id=? AND w.applicant_type='RIDER'
        """.formatted(WITHDRAWAL_STATUS);
    private final JdbcTemplate db;

    public RiderFinance(JdbcTemplate db) {
        this.db = db;
    }

    public Map<String, Object> summary(String rider) {
        var totals = db.queryForMap("""
            SELECT COUNT(*) AS orderCount,COALESCE(SUM(%s),0) AS feeCents,
                   COALESCE(SUM(%s),0) AS commissionCents,
                   COALESCE(SUM(%s),0) AS dueCents,
                   COALESCE(SUM(IF(o.refunded_cents>0 AND o.paid_cents=o.refunded_cents,%s,0)),0) AS fundedCents,
                   COALESCE(SUM(IF(d.rider_settled_at IS NULL AND (o.pending_refund OR o.locking OR
                       o.refund_status NOT IN ('NO_AFTER_SALE','AFTER_SALE_ENDED','REFUND_SUCCEEDED','OFFLINE_RESOLVED')),
                       %s,0)),0) AS heldCents,
                   COALESCE(SUM(IF(d.rider_settled_at IS NULL AND NOT (o.pending_refund OR o.locking OR
                       o.refund_status NOT IN ('NO_AFTER_SALE','AFTER_SALE_ENDED','REFUND_SUCCEEDED','OFFLINE_RESOLVED')),
                       %s,0)),0) AS pendingCents,
                   COALESCE(SUM(o.completed_at>=? AND o.fulfill_status='COMPLETED'),0) AS todayCompleted
            FROM user_order_detail d JOIN business_order o ON o.id=d.order_id %s WHERE d.pickup_rider_id=?
            """.formatted(RiderOrders.FEE,RiderOrders.COMMISSION,RiderOrders.DUE,RiderOrders.DUE,RiderOrders.DUE,
                RiderOrders.DUE,RiderOrders.SETTLEMENT_JOIN), java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"))
                .atStartOfDay(java.time.ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli(), rider);
        totals.putAll(db.queryForList("""
            SELECT balance_cents AS availableCents,frozen_cents AS frozenCents
            FROM finance_account WHERE id=? AND type='RIDER'
            """, rider).stream().findFirst().orElse(Map.of("availableCents", 0, "frozenCents", 0)));
        totals.put("minimumWithdrawalCents", WithdrawalLimits.MINIMUM_CENTS);
        totals.put("paidOutCents", db.queryForObject("""
            SELECT COALESCE(SUM(amount_cents),0) FROM withdrawal_request
            WHERE account_id=? AND applicant_type='RIDER' AND status='PAID'
            """, Long.class, rider));
        totals.put("pendingReceiptCount", db.queryForObject("""
            SELECT COUNT(*) FROM withdrawal_request w
            LEFT JOIN rider_transfer_channel c ON c.withdrawal_id=w.id
            WHERE w.account_id=? AND w.applicant_type='RIDER' AND (%s)='AWAITING_CONFIRMATION'
            """.formatted(WITHDRAWAL_STATUS), Long.class, rider));
        return totals;
    }

    public List<Map<String, Object>> withdrawals(String rider, int offset) {
        return db.queryForList("""
            SELECT records.* FROM (%s) records
            ORDER BY (records.status='AWAITING_CONFIRMATION') DESC,records.createdAt DESC,records.id DESC
            LIMIT 30 OFFSET ?
            """.formatted(WITHDRAWALS), rider, Math.max(0, offset));
    }

    public Map<String, Object> withdrawal(String rider, String id) {
        var rows = db.queryForList(WITHDRAWALS + " AND w.id=?", rider, id);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "提现记录不存在");
        }
        var result = rows.getFirst();
        result.put("timeline", db.queryForList("""
            SELECT COALESCE(after_status,after_state) AS status,reason,UNIX_TIMESTAMP(created_at)*1000 AS createdAt
            FROM admin_audit_log WHERE target_id=? AND action_name IN ('withdrawal.review','withdrawal.channel')
            ORDER BY id
            """, id));
        if (Withdrawal.AWAITING_CONFIRMATION.is(result.get("status"))) {
            var parameters = db.queryForList("""
                SELECT c.app_id AS appId,c.merchant_no AS mchId,c.package_info AS package
                FROM rider_transfer_channel c JOIN withdrawal_request w ON w.id=c.withdrawal_id
                JOIN rider_identity i ON i.rider_id=w.account_id AND i.app_id=c.app_id
                  AND (c.recipient_open_id IS NULL OR c.recipient_open_id=i.openid)
                WHERE w.id=? AND w.account_id=? AND w.status IN ('TRANSFERRING','AWAITING_CONFIRMATION')
                  AND c.channel_state='WAIT_USER_CONFIRM' AND c.package_info IS NOT NULL AND c.checked_at>=?
                """, id, rider, System.currentTimeMillis() - 120_000L);
            parameters.stream().findFirst().ifPresent(value -> result.put("confirmation", value));
        }
        return result;
    }
}

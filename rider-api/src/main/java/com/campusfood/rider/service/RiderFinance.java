package com.campusfood.rider.service;

import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RiderFinance {
    private static final String WITHDRAWALS = """
        SELECT w.id,w.amount_cents AS amountCents,
               CASE WHEN w.status='打款中' AND c.channel_state='WAIT_USER_CONFIRM' THEN '待收款确认'
                    WHEN w.status='打款中' AND c.channel_state='UNKNOWN' THEN '结果待确认'
                    ELSE w.status END AS status,w.created_at AS createdAt,w.reason,
               t.status AS transferStatus,t.reason AS transferReason
        FROM withdrawal_request w LEFT JOIN withdrawal_transfer t ON t.withdrawal_id=w.id
        LEFT JOIN rider_transfer_channel c ON c.withdrawal_id=w.id
        WHERE w.account_id=? AND w.applicant_type='骑手'
        """;
    private final JdbcTemplate db;

    public RiderFinance(JdbcTemplate db) {
        this.db = db;
    }

    public Map<String, Object> summary(String rider) {
        var totals = db.queryForMap("""
            SELECT COUNT(*) AS orderCount,COALESCE(SUM(o.delivery_cents),0) AS feeCents,
                   COALESCE(SUM(d.rider_commission_cents),0) AS commissionCents,
                   COALESCE(SUM(o.delivery_cents-d.rider_commission_cents),0) AS dueCents,
                   COALESCE(SUM(IF(d.rider_settled_at IS NULL AND (o.pending_refund OR o.locking OR
                       o.refund_status NOT IN ('无售后','已结束','退款成功','线下已处理')),
                       o.delivery_cents-d.rider_commission_cents,0)),0) AS heldCents,
                   COALESCE(SUM(IF(d.rider_settled_at IS NULL AND NOT (o.pending_refund OR o.locking OR
                       o.refund_status NOT IN ('无售后','已结束','退款成功','线下已处理')),
                       o.delivery_cents-d.rider_commission_cents,0)),0) AS pendingCents,
                   COALESCE(SUM(o.completed_at>=? AND o.fulfill_status='已完成'),0) AS todayCompleted
            FROM user_order_detail d JOIN business_order o ON o.id=d.order_id WHERE d.pickup_rider_id=?
            """, java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"))
                .atStartOfDay(java.time.ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli(), rider);
        totals.putAll(db.queryForList("""
            SELECT balance_cents AS availableCents,frozen_cents AS frozenCents
            FROM finance_account WHERE id=? AND type='骑手'
            """, rider).stream().findFirst().orElse(Map.of("availableCents", 0, "frozenCents", 0)));
        totals.put("paidOutCents", db.queryForObject("""
            SELECT COALESCE(SUM(amount_cents),0) FROM withdrawal_request
            WHERE account_id=? AND applicant_type='骑手' AND status='打款成功'
            """, Long.class, rider));
        return totals;
    }

    public List<Map<String, Object>> withdrawals(String rider, int offset) {
        return db.queryForList(WITHDRAWALS + " ORDER BY w.created_at DESC,w.id DESC LIMIT 30 OFFSET ?",
            rider, Math.max(0, offset));
    }

    public Map<String, Object> withdrawal(String rider, String id) {
        var rows = db.queryForList(WITHDRAWALS + " AND w.id=?", rider, id);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "提现记录不存在");
        }
        var result = rows.getFirst();
        result.put("timeline", db.queryForList("""
            SELECT after_state AS status,reason,UNIX_TIMESTAMP(created_at)*1000 AS createdAt
            FROM admin_audit_log WHERE target_id=? AND action_name IN ('withdrawal.review','withdrawal.channel')
            ORDER BY id
            """, id));
        if ("待收款确认".equals(result.get("status"))) {
            var parameters = db.queryForList("""
                SELECT c.app_id AS appId,c.merchant_no AS mchId,c.package_info AS package
                FROM rider_transfer_channel c JOIN withdrawal_request w ON w.id=c.withdrawal_id
                JOIN rider_identity i ON i.id=w.account_id AND i.app_id=c.app_id
                WHERE w.id=? AND w.account_id=? AND w.status='打款中'
                  AND c.channel_state='WAIT_USER_CONFIRM' AND c.package_info IS NOT NULL AND c.checked_at>=?
                """, id, rider, System.currentTimeMillis() - 120_000L);
            parameters.stream().findFirst().ifPresent(value -> result.put("confirmation", value));
        }
        return result;
    }
}

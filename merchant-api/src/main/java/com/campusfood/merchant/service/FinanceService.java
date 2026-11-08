package com.campusfood.merchant.service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

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
            FROM finance_account WHERE id=? AND type='商户'
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
                       d.food_rate_snapshot AS foodRate,d.merchant_settled_cents AS settledCents,
                       d.merchant_settled_at AS settledAt,o.settlement_status AS status,o.completed_at AS completedAt
                FROM business_order o JOIN user_order_detail d ON d.order_id=o.id
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
                SELECT id,amount_cents AS amountCents,status,created_at AS createdAt,reason
                FROM withdrawal_request WHERE account_id=? AND applicant_type='商户' ORDER BY created_at DESC,id DESC LIMIT 100
                """;
            default -> throw Inputs.invalid("未知财务记录类型");
        };
        return db.queryForList(sql, merchant);
    }
}

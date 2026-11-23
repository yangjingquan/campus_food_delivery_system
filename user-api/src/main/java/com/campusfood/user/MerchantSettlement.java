package com.campusfood.user;

import java.time.Duration;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class MerchantSettlement {
    private static final long WAIT_MILLIS = Duration.ofHours(24).toMillis();
    private static final String ELIGIBLE = """
        FROM business_order o JOIN user_order_detail d ON d.order_id=o.id
        JOIN payment_order p ON p.id=o.payment_id
        JOIN finance_account a ON a.id=o.merchant_id AND a.type='MERCHANT' AND a.status='ACCOUNT_ACTIVE'
        LEFT JOIN order_pricing_rule pricing ON pricing.order_id=o.id
        LEFT JOIN operations_config original ON original.id=d.operations_rule_id
        WHERE o.payment_status='PAYMENT_SUCCEEDED' AND p.status='PAYMENT_SUCCEEDED' AND p.payment_mode='wechat'
          AND o.pending_refund=FALSE AND o.locking=FALSE AND d.merchant_settled_at IS NULL
          AND (pricing.order_id IS NOT NULL OR d.food_rate_snapshot IS NOT NULL OR original.id IS NOT NULL)
          AND NOT EXISTS (SELECT 1 FROM refund_record r WHERE r.order_id=o.id
                AND r.status IN ('AWAITING_MERCHANT_DECISION','NEGOTIATING','REFUND_PROCESSING',
                                 'REFUND_VERIFYING','REFUND_QUEUED','PLATFORM_REVIEW','REFUND_FAILED'))
          AND ((o.fulfill_status='COMPLETED' AND o.completed_at<? AND o.refunded_cents=0
                AND o.settlement_status='PENDING_SETTLEMENT' AND o.refund_status IN ('NO_AFTER_SALE','AFTER_SALE_ENDED'))
            OR (o.fulfill_status='CLOSED' AND o.last_refund_at<?
                AND o.refunded_cents>0 AND o.refunded_cents<o.paid_cents
                AND o.settlement_status='SETTLEMENT_HELD'
                AND o.refund_status IN ('REFUND_SUCCEEDED','OFFLINE_RESOLVED','AFTER_SALE_ENDED')))
        """;

    private final JdbcTemplate db;
    private final TransactionTemplate transactions;

    MerchantSettlement(JdbcTemplate db, PlatformTransactionManager manager) {
        this.db = db;
        this.transactions = new TransactionTemplate(manager);
    }

    @Scheduled(fixedDelayString = "${app.merchant-settlement-scan-ms:60000}", initialDelay = 15000)
    void scan() {
        long cutoff = System.currentTimeMillis() - WAIT_MILLIS;
        var due = db.queryForList("SELECT o.id " + ELIGIBLE + " ORDER BY COALESCE(o.last_refund_at,o.completed_at),o.id LIMIT 50", String.class, cutoff, cutoff);
        for (String order : due) {
            transactions.executeWithoutResult(status -> credit(order, cutoff));
        }
    }

    private void credit(String id, long cutoff) {
        var rows = db.queryForList("""
            SELECT o.merchant_id,o.food_cents,o.delivery_cents,o.paid_cents,o.refunded_cents,o.food_refunded_cents,
                   d.rider_commission_cents
            """ + ELIGIBLE + " AND o.id=? FOR UPDATE", cutoff, cutoff, id);
        if (rows.isEmpty()) return;
        var order = rows.getFirst();
        String merchant = order.get("merchant_id").toString();
        var settlement = OrderPricing.settlement(db, id, order);
        long net = settlement.merchantDue();
        long now = System.currentTimeMillis();
        db.update("UPDATE finance_account SET balance_cents=balance_cents+? WHERE id=?", net, merchant);
        db.update("""
            INSERT INTO account_journal(account_id,event_type,reference_id,available_delta,frozen_delta)
            VALUES(?,'settlement_credit',?,?,0)
            """, merchant, id, net);
        OrderPricing.recordSettlement(db, id, "settlement_credit", merchant, settlement, net, now);
        recoverAdvances(merchant, id);
        db.update("UPDATE business_order SET settlement_status='WITHDRAWABLE' WHERE id=?", id);
        db.update("UPDATE user_order_detail SET merchant_settled_cents=?,merchant_settled_at=? WHERE order_id=?",
            net, now, id);
    }

    private void recoverAdvances(String merchant, String order) {
        // 商户余额入账时及时归还已垫付欠款。账户已锁定，与提现扣回使用同一账户锁和欠款顺序。
        long available = db.queryForObject("SELECT balance_cents FROM finance_account WHERE id=?", Long.class, merchant);
        var debts = db.queryForList("""
            SELECT id,platform_advance_cents-recovered_cents AS outstanding
            FROM finance_debt WHERE merchant_account_id=? AND platform_advance_cents>recovered_cents
            ORDER BY id FOR UPDATE
            """, merchant);
        for (var debt : debts) {
            long recovered = Math.min(available, ((Number) debt.get("outstanding")).longValue());
            if (recovered == 0) {
                return;
            }
            String id = debt.get("id").toString();
            db.update("UPDATE finance_debt SET recovered_cents=recovered_cents+? WHERE id=?", recovered, id);
            db.update("UPDATE finance_account SET balance_cents=balance_cents-? WHERE id=?", recovered, merchant);
            db.update("UPDATE finance_account SET balance_cents=balance_cents+? WHERE id='SYSTEM_PLATFORM'", recovered);
            String reference = java.util.UUID.nameUUIDFromBytes((order + ":" + id)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
            db.update("""
                INSERT INTO account_journal(account_id,event_type,reference_id,available_delta,frozen_delta,debt_id)
                VALUES(?,'debt_recover',?, ?,0,?),('SYSTEM_PLATFORM','debt_receive',?, ?,0,?)
                """, merchant, reference, -recovered, id, reference, recovered, id);
            available -= recovered;
        }
    }
}

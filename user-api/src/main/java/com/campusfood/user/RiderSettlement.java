package com.campusfood.user;

import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class RiderSettlement {
    private static final String ELIGIBLE_AT = """
        (CASE WHEN o.fulfill_status='CLOSED' THEN o.last_refund_at ELSE o.completed_at END)
        + d.rider_settlement_wait_minutes * 60000
        """.strip();
    private static final String ELIGIBLE = """
        FROM business_order o JOIN user_order_detail d ON d.order_id=o.id
        JOIN payment_order p ON p.id=o.payment_id
        WHERE p.payment_mode='wechat' AND p.status='PAYMENT_SUCCEEDED'
          AND d.pickup_rider_id IS NOT NULL AND d.rider_settled_at IS NULL
          AND o.pending_refund=FALSE AND o.locking=FALSE
          AND ((o.fulfill_status='COMPLETED' AND o.refund_status IN ('NO_AFTER_SALE','AFTER_SALE_ENDED'))
               OR (o.fulfill_status='CLOSED' AND o.refund_status IN ('REFUND_SUCCEEDED','OFFLINE_RESOLVED','AFTER_SALE_ENDED')))
        """ + " AND (" + ELIGIBLE_AT + ") <= ?";

    private final JdbcTemplate db;
    private final TransactionTemplate transactions;

    RiderSettlement(JdbcTemplate db, PlatformTransactionManager manager) {
        this.db = db;
        this.transactions = new TransactionTemplate(manager);
    }

    @Scheduled(fixedDelayString = "${app.rider-settlement-scan-ms:60000}", initialDelay = 20000)
    void scan() {
        long scanAt = System.currentTimeMillis();
        var ids = db.queryForList("SELECT o.id " + ELIGIBLE + " ORDER BY " + ELIGIBLE_AT + ",o.id LIMIT 50",
            String.class, scanAt);
        for (String id : ids) {
            transactions.executeWithoutResult(status -> credit(id, scanAt));
        }
    }

    private void credit(String id, long scanAt) {
        var orders = db.queryForList("""
            SELECT o.merchant_id,o.merchant,o.rider,o.food_cents,o.delivery_cents,o.paid_cents,o.refunded_cents,
                   o.food_refunded_cents,d.pickup_rider_id,d.rider_commission_cents,d.rider_settlement_wait_minutes AS wait_minutes,
                   %s AS eligible_at
            """.formatted(ELIGIBLE_AT) + ELIGIBLE + " AND o.id=? FOR UPDATE", scanAt, id);
        if (orders.isEmpty()) {
            return;
        }
        var order = orders.getFirst();
        String rider = order.get("pickup_rider_id").toString();
        var settlement = OrderPricing.settlement(db, id, order);
        var timing = OrderPricing.Timing.from(order);
        long due = settlement.riderDue();
        var riderAccount = db.queryForList("SELECT id FROM finance_account WHERE id=? AND type='RIDER' FOR UPDATE",
            String.class, rider);
        if (riderAccount.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (settlement.fullyRefunded() && due > 0) {
            chargeMerchant(order, id, settlement, now, timing);
        }
        db.update("UPDATE finance_account SET balance_cents=balance_cents+? WHERE id=?", due, rider);
        journal(rider, "rider_settlement_credit", id, due);
        OrderPricing.recordSettlement(db, id, "rider_settlement_credit", rider, settlement, due, now, timing);
        db.update("UPDATE user_order_detail SET rider_settled_at=?,rider_commission_cents=? WHERE order_id=?",
            now, settlement.riderCalculationCommission(), id);
    }

    private void chargeMerchant(Map<String, Object> order, String id, OrderPricing.Settlement settlement, long now,
                                OrderPricing.Timing timing) {
        long due = settlement.riderDue();
        String merchant = order.get("merchant_id").toString();
        long balance = db.queryForList("SELECT balance_cents FROM finance_account WHERE id=? AND type='MERCHANT' FOR UPDATE",
            Long.class, merchant).stream().findFirst().orElse(0L);
        long debit = Math.min(balance, due);
        if (debit > 0) {
            db.update("UPDATE finance_account SET balance_cents=balance_cents-? WHERE id=?", debit, merchant);
            journal(merchant, "rider_refund_debit", id, -debit);
            OrderPricing.recordSettlement(db, id, "rider_refund_debit", merchant, settlement, -debit, now, timing);
        }
        long advance = due - debit;
        if (advance > 0) {
            // 经营资金的实际垫付单独记账，欠款通过管理端既有的商户提现扣回流程归还。
            db.update("""
                INSERT INTO finance_debt(id,order_id,merchant,rider,rider_due_cents,merchant_balance_cents,
                                         platform_advance_cents,note,merchant_account_id)
                VALUES(?,?,?,?,?,?,?,'已领餐全额退款，平台垫付骑手收入',?)
                """, "RD-" + java.util.UUID.randomUUID(), id, order.get("merchant"), order.get("rider"), due, balance, advance, merchant);
            journal("SYSTEM_PLATFORM", "rider_refund_advance", id, -advance);
            OrderPricing.recordSettlement(db, id, "rider_refund_advance", "SYSTEM_PLATFORM", settlement, -advance, now, timing);
        }
    }

    private void journal(String account, String type, String order, long delta) {
        db.update("""
            INSERT INTO account_journal(account_id,event_type,reference_id,available_delta,frozen_delta)
            VALUES(?,?,?,?,0)
            """, account, type, order, delta);
    }
}

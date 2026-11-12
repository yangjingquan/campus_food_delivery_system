package com.campusfood.user;

import java.time.Duration;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class RiderSettlement {
    private static final String ELIGIBLE = """
        FROM business_order o JOIN user_order_detail d ON d.order_id=o.id
        JOIN payment_order p ON p.id=o.payment_id
        WHERE p.payment_mode='wechat' AND p.status='支付成功'
          AND d.pickup_rider_id IS NOT NULL AND d.rider_settled_at IS NULL
          AND o.pending_refund=FALSE AND o.locking=FALSE
          AND ((o.fulfill_status='已完成' AND o.refund_status IN ('无售后','已结束') AND o.completed_at<?)
               OR (o.fulfill_status='已关闭' AND o.refund_status IN ('退款成功','线下已处理','已结束') AND o.last_refund_at<?))
        """;

    private final JdbcTemplate db;
    private final TransactionTemplate transactions;

    RiderSettlement(JdbcTemplate db, PlatformTransactionManager manager) {
        this.db = db;
        this.transactions = new TransactionTemplate(manager);
    }

    @Scheduled(fixedDelayString = "${app.rider-settlement-scan-ms:60000}", initialDelay = 20000)
    void scan() {
        long cutoff = System.currentTimeMillis() - Duration.ofHours(24).toMillis();
        var ids = db.queryForList("SELECT o.id " + ELIGIBLE + """
            ORDER BY COALESCE(o.last_refund_at,o.completed_at),o.id LIMIT 50
            """, String.class, cutoff, cutoff);
        for (String id : ids) {
            transactions.executeWithoutResult(status -> credit(id, cutoff));
        }
    }

    private void credit(String id, long cutoff) {
        // 与退款共用订单锁。历史已领取订单缺少费率快照时，优先采用成交时的规则版本。
        var orders = db.queryForList("""
            SELECT o.merchant_id,o.merchant,o.rider,o.delivery_cents,o.paid_cents,o.refunded_cents,
                   d.pickup_rider_id,
                   COALESCE(d.rider_commission_cents,ROUND(o.delivery_cents * COALESCE(
                       (SELECT delivery_rate FROM operations_config WHERE id=d.operations_rule_id),
                       (SELECT delivery_rate FROM operations_config ORDER BY id DESC LIMIT 1)) / 100)) AS commission
            """ + ELIGIBLE + " AND o.id=? FOR UPDATE", cutoff, cutoff, id);
        if (orders.isEmpty()) {
            return;
        }
        var order = orders.getFirst();
        String rider = order.get("pickup_rider_id").toString();
        long commission = number(order, "commission");
        long due = number(order, "delivery_cents") - commission;
        var riderAccount = db.queryForList("SELECT id FROM finance_account WHERE id=? AND type='骑手' FOR UPDATE",
            String.class, rider);
        if (riderAccount.isEmpty()) {
            return;
        }
        if (number(order, "paid_cents") == number(order, "refunded_cents") && due > 0) {
            chargeMerchant(order, id, due);
        }
        db.update("UPDATE finance_account SET balance_cents=balance_cents+? WHERE id=?", due, rider);
        journal(rider, "rider_settlement_credit", id, due);
        db.update("UPDATE user_order_detail SET rider_settled_at=?,rider_commission_cents=? WHERE order_id=?",
            System.currentTimeMillis(), commission, id);
    }

    private void chargeMerchant(Map<String, Object> order, String id, long due) {
        String merchant = order.get("merchant_id").toString();
        long balance = db.queryForList("SELECT balance_cents FROM finance_account WHERE id=? AND type='商户' FOR UPDATE",
            Long.class, merchant).stream().findFirst().orElse(0L);
        long debit = Math.min(balance, due);
        if (debit > 0) {
            db.update("UPDATE finance_account SET balance_cents=balance_cents-? WHERE id=?", debit, merchant);
            journal(merchant, "rider_refund_debit", id, -debit);
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
        }
    }

    private void journal(String account, String type, String order, long delta) {
        db.update("""
            INSERT INTO account_journal(account_id,event_type,reference_id,available_delta,frozen_delta)
            VALUES(?,?,?,?,0)
            """, account, type, order, delta);
    }

    private static long number(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }
}

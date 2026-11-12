package com.campusfood.user;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
        JOIN finance_account a ON a.id=o.merchant_id AND a.type='商户' AND a.status='正常'
        WHERE o.payment_status='支付成功' AND p.status='支付成功' AND p.payment_mode='wechat'
          AND o.fulfill_status='已完成' AND o.completed_at<?
          AND o.settlement_status='待结算' AND o.refunded_cents=0 AND o.pending_refund=FALSE
          AND o.locking=FALSE AND o.refund_status IN ('无售后','已结束')
          AND d.food_rate_snapshot BETWEEN 0 AND 100 AND d.merchant_settled_at IS NULL
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
        var due = db.queryForList("SELECT o.id " + ELIGIBLE + " ORDER BY o.completed_at,o.id LIMIT 50", String.class, cutoff);
        for (String order : due) {
            transactions.executeWithoutResult(status -> credit(order, cutoff));
        }
    }

    private void credit(String id, long cutoff) {
        var rows = db.query("""
            SELECT o.merchant_id,o.food_cents,d.food_rate_snapshot
            """ + ELIGIBLE + " AND o.id=? FOR UPDATE",
            (row, index) -> new Credit(row.getString(1), row.getLong(2), row.getBigDecimal(3)), cutoff, id);
        if (rows.isEmpty()) return;
        Credit credit = rows.getFirst();

        // 仅处理有成交规则快照的正常真实支付单。沿用管理端的四舍五入到分口径。
        long commission = BigDecimal.valueOf(credit.foodCents()).multiply(credit.foodRate())
            .movePointLeft(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
        long net = credit.foodCents() - commission;
        long now = System.currentTimeMillis();
        db.update("UPDATE finance_account SET balance_cents=balance_cents+? WHERE id=?", net, credit.merchant());
        db.update("""
            INSERT INTO account_journal(account_id,event_type,reference_id,available_delta,frozen_delta)
            VALUES(?,'settlement_credit',?,?,0)
            """, credit.merchant(), id, net);
        recoverAdvances(credit.merchant(), id);
        db.update("UPDATE business_order SET settlement_status='可提现' WHERE id=?", id);
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

    private record Credit(String merchant, long foodCents, BigDecimal foodRate) {
    }
}

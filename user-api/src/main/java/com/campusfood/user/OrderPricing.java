package com.campusfood.user;

import com.campusfood.domain.PricingRules;
import com.campusfood.domain.PricingRules.Mode;
import com.campusfood.domain.PricingRules.Rule;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

final class OrderPricing {
    record Applied(String source, long version, long defaultVersion, PricingRules rules,
                   int merchantWaitMinutes, int riderWaitMinutes) {}

    record Timing(int waitMinutes, long eligibleAt) {
        static Timing from(Map<String, Object> order) {
            return new Timing(Math.toIntExact(number(order, "wait_minutes")), number(order, "eligible_at"));
        }
    }

    record Settlement(Applied pricing, long food, long delivery, long paid, long refunded,
                      long foodRefunded, Long pickupCommission) {
        boolean fullyRefunded() { return refunded > 0 && paid == refunded; }
        long foodBase() { return food - foodRefunded; }
        long deliveryRefunded() { return refunded - foodRefunded; }
        long deliveryBase() { return delivery - deliveryRefunded(); }
        long foodCommission() { return fullyRefunded() ? 0 : pricing.rules().food().commission(foodBase()); }
        long riderBase() { return fullyRefunded() ? delivery : deliveryBase(); }
        long riderCalculationCommission() {
            return pickupCommission != null && riderBase() == delivery ? pickupCommission
                : pricing.rules().delivery().commission(riderBase());
        }
        long deliveryCommission() { return fullyRefunded() ? 0 : riderCalculationCommission(); }
        long merchantDue() { return foodBase() - foodCommission(); }
        long riderDue() { return riderBase() - riderCalculationCommission(); }
    }

    private OrderPricing() {}

    static Map<String, Applied> current(JdbcTemplate db, Collection<String> merchants) {
        if (merchants.isEmpty()) return Map.of();
        return new NamedParameterJdbcTemplate(db).query("""
            SELECT m.id,c.id AS default_version,COALESCE(r.version,c.id) AS version,
                   c.merchant_settlement_wait_minutes,c.rider_settlement_wait_minutes,
                   IF(r.version IS NULL,'DEFAULT','MERCHANT') AS source,
                   COALESCE(r.fee_mode,IF(c.fee_bps IS NULL,'FIXED','PERCENTAGE')) AS fee_mode,
                   COALESCE(r.fee_value,c.fee_bps,c.fee_cents) AS fee_value,
                   COALESCE(r.food_mode,IF(c.food_fixed_cents IS NULL,'PERCENTAGE','FIXED')) AS food_mode,
                   COALESCE(r.food_value,c.food_fixed_cents,c.food_rate*100) AS food_value,
                   COALESCE(r.delivery_mode,IF(c.delivery_fixed_cents IS NULL,'PERCENTAGE','FIXED')) AS delivery_mode,
                   COALESCE(r.delivery_value,c.delivery_fixed_cents,c.delivery_rate*100) AS delivery_value
            FROM merchant m CROSS JOIN (SELECT * FROM operations_config ORDER BY id DESC LIMIT 1) c
            LEFT JOIN merchant_pricing_rule r ON r.merchant_id=m.id AND r.enabled=TRUE
            WHERE m.id IN (:merchants)
            """, Map.of("merchants", merchants), rs -> {
                var result = new java.util.HashMap<String, Applied>();
                while (rs.next()) result.put(rs.getString("id"), read(rs));
                return result;
            });
    }

    static void lock(JdbcTemplate db, String orderId, Applied pricing) {
        var rules = pricing.rules();
        db.update("""
            INSERT INTO order_pricing_rule(order_id,source,version,fee_mode,fee_value,food_mode,food_value,
                delivery_mode,delivery_value) VALUES(?,?,?,?,?,?,?,?,?)
            """, orderId, pricing.source(), pricing.version(), rules.fee().mode().name(), rules.fee().value(),
            rules.food().mode().name(), rules.food().value(), rules.delivery().mode().name(), rules.delivery().value());
    }

    static Applied applied(JdbcTemplate db, String orderId) {
        List<Applied> rows = db.query("""
            SELECT COALESCE(r.source,'LEGACY') AS source,COALESCE(r.version,c.id,fallback.id) AS version,
                   COALESCE(c.id,fallback.id) AS default_version,
                   d.merchant_settlement_wait_minutes,d.rider_settlement_wait_minutes,
                   COALESCE(r.fee_mode,'FIXED') AS fee_mode,
                   COALESCE(r.fee_value,c.fee_cents,o.delivery_cents) AS fee_value,
                   COALESCE(r.food_mode,'PERCENTAGE') AS food_mode,
                   COALESCE(r.food_value,d.food_rate_snapshot*100,c.food_rate*100,fallback.food_rate*100) AS food_value,
                   COALESCE(r.delivery_mode,'PERCENTAGE') AS delivery_mode,
                   COALESCE(r.delivery_value,c.delivery_rate*100,fallback.delivery_rate*100) AS delivery_value
            FROM business_order o JOIN user_order_detail d ON d.order_id=o.id
            LEFT JOIN order_pricing_rule r ON r.order_id=o.id
            LEFT JOIN operations_config c ON c.id=d.operations_rule_id
            JOIN (SELECT * FROM operations_config WHERE NOT pricing_versioned ORDER BY id DESC LIMIT 1) fallback
            WHERE o.id=?
            """, (rs, index) -> read(rs), orderId);
        if (rows.isEmpty()) throw new IllegalStateException("订单缺少计费规则");
        return rows.getFirst();
    }

    private static Applied read(ResultSet rs) throws SQLException {
        return new Applied(rs.getString("source"), rs.getLong("version"), rs.getLong("default_version"),
            new PricingRules(rule(rs, "fee"), rule(rs, "food"), rule(rs, "delivery")),
            rs.getInt("merchant_settlement_wait_minutes"), rs.getInt("rider_settlement_wait_minutes"));
    }

    private static Rule rule(ResultSet rs, String name) throws SQLException {
        return new Rule(Mode.valueOf(rs.getString(name + "_mode")), rs.getInt(name + "_value"));
    }

    static Settlement settlement(JdbcTemplate db, String id, Map<String, Object> order) {
        return new Settlement(applied(db, id), number(order, "food_cents"), number(order, "delivery_cents"),
            number(order, "paid_cents"), number(order, "refunded_cents"), number(order, "food_refunded_cents"),
            order.get("rider_commission_cents") instanceof Number n ? n.longValue() : null);
    }

    static void recordSettlement(JdbcTemplate db, String id, String event, String account, Settlement settlement,
                                 long amount, long now, Timing timing) {
        var applied = settlement.pricing();
        var rules = applied.rules();
        db.update("""
            INSERT INTO order_settlement_snapshot(order_id,event_type,account_id,rule_source,rule_version,rules,
                food_base_cents,delivery_base_cents,original_delivery_cents,food_refunded_cents,delivery_refunded_cents,
                food_commission_cents,delivery_commission_cents,rider_calculation_commission_cents,rider_due_cents,amount_cents,settled_at,
                settlement_wait_minutes,eligible_at)
            VALUES(?,?,?,?,?,JSON_OBJECT('fee',JSON_OBJECT('mode',?,'value',?),
                'food',JSON_OBJECT('mode',?,'value',?),'delivery',JSON_OBJECT('mode',?,'value',?)),?,?,?,?,?,?,?,?,?,?,?,?,?)
            """, id, event, account, applied.source(), applied.version(), rules.fee().mode().name(), rules.fee().value(),
            rules.food().mode().name(), rules.food().value(), rules.delivery().mode().name(), rules.delivery().value(),
            settlement.foodBase(), settlement.deliveryBase(), settlement.delivery(), settlement.foodRefunded(),
            settlement.deliveryRefunded(), settlement.foodCommission(), settlement.deliveryCommission(),
            settlement.riderCalculationCommission(), settlement.riderDue(), amount, now, timing.waitMinutes(), timing.eligibleAt());
    }

    private static long number(Map<String, Object> order, String key) {
        return ((Number) order.get(key)).longValue();
    }
}

package com.campusfood.admin.service;

import com.campusfood.admin.dao.BusinessDao;
import com.campusfood.domain.PricingRules;
import com.campusfood.domain.PricingRules.Mode;
import com.campusfood.domain.PricingRules.Rule;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PricingConfigurationService {
    private final JdbcTemplate db;
    private final BusinessDao audit;

    public PricingConfigurationService(JdbcTemplate db, BusinessDao audit) {
        this.db = db;
        this.audit = audit;
    }

    static PricingRules parse(Object input) {
        if (!(input instanceof Map<?, ?> values)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请填写三项计费规则");
        }
        return new PricingRules(parseRule(values.get("fee"), "配送费"),
            parseRule(values.get("food"), "餐品平台抽成"), parseRule(values.get("delivery"), "配送费平台抽成"));
    }

    private static Rule parseRule(Object value, String label) {
        try {
            if (value instanceof Map<?, ?> rule && rule.get("value") instanceof Number number) {
                return new Rule(Mode.valueOf(Objects.toString(rule.get("mode"), "")), Integer.parseInt(number.toString()));
            }
        } catch (IllegalArgumentException ignored) {
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + "的计费方式或数值无效");
    }

    public Map<String, Object> merchant(String id) {
        requireMerchant(id, false);
        return currentMerchant(id);
    }

    private Map<String, Object> currentMerchant(String id) {
        return db.query("""
            SELECT version,enabled,fee_mode,fee_value,food_mode,food_value,delivery_mode,delivery_value
            FROM merchant_pricing_rule WHERE merchant_id=?
            """, rs -> {
            if (!rs.next()) return Map.of("version", 0, "enabled", false);
            var rules = new PricingRules(new Rule(Mode.valueOf(rs.getString(3)), rs.getInt(4)),
                new Rule(Mode.valueOf(rs.getString(5)), rs.getInt(6)),
                new Rule(Mode.valueOf(rs.getString(7)), rs.getInt(8)));
            return Map.of("version", rs.getLong(1), "enabled", rs.getBoolean(2), "pricing", rules);
        }, id);
    }

    @Transactional
    public Map<String, Object> saveMerchant(Map<?, ?> payload, String actor) {
        String id = Objects.toString(payload.get("id"), "");
        requireMerchant(id, true);
        var current = currentMerchant(id);
        long previous = ((Number) current.get("version")).longValue();
        if (!Long.toString(previous).equals(Objects.toString(payload.get("version")))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该商户规则已更新，请刷新后重试");
        }
        if (!(payload.get("enabled") instanceof Boolean enabled)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择是否启用商户规则");
        }
        var rules = parse(payload.get("pricing"));
        long version = previous + 1;
        if (previous == 0) {
            db.update("""
                INSERT INTO merchant_pricing_rule(version,enabled,fee_mode,fee_value,food_mode,food_value,
                    delivery_mode,delivery_value,updated_by,merchant_id) VALUES(?,?,?,?,?,?,?,?,?,?)
                """, version, enabled, rules.fee().mode().name(), rules.fee().value(), rules.food().mode().name(),
                rules.food().value(), rules.delivery().mode().name(), rules.delivery().value(), actor, id);
        } else {
            db.update("""
                UPDATE merchant_pricing_rule SET version=?,enabled=?,fee_mode=?,fee_value=?,food_mode=?,food_value=?,
                    delivery_mode=?,delivery_value=?,updated_by=? WHERE merchant_id=?
                """, version, enabled, rules.fee().mode().name(), rules.fee().value(), rules.food().mode().name(),
                rules.food().value(), rules.delivery().mode().name(), rules.delivery().value(), actor, id);
        }
        audit.insertAudit(actor, "merchant.save-pricing", id, Long.toString(previous), Long.toString(version),
            "enabled=" + enabled + ", pricing=" + rules);
        return Map.of("version", version, "enabled", enabled, "pricing", rules);
    }

    public List<Map<String, Object>> snapshots(String orderId) {
        return db.queryForList("""
            SELECT event_type AS eventType,account_id AS accountId,rule_source AS ruleSource,
                   rule_version AS ruleVersion,CAST(rules AS CHAR) AS ruleDetails,
                   food_base_cents AS foodBaseCents,delivery_base_cents AS deliveryBaseCents,
                   original_delivery_cents AS originalDeliveryCents,food_refunded_cents AS foodRefundedCents,
                   delivery_refunded_cents AS deliveryRefundedCents,food_commission_cents AS foodCommissionCents,
                   delivery_commission_cents AS deliveryCommissionCents,rider_due_cents AS riderDueCents,
                   amount_cents AS amountCents,settled_at AS settledAt,
                   settlement_wait_minutes AS settlementWaitMinutes,eligible_at AS eligibleAt
            FROM order_settlement_snapshot WHERE order_id=? ORDER BY settled_at,event_type
            """, orderId);
    }

    private void requireMerchant(String id, boolean lock) {
        List<String> rows = db.queryForList("SELECT id FROM merchant WHERE id=? AND deleted_at IS NULL"
            + (lock ? " FOR UPDATE" : ""), String.class, id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "商户不存在");
    }
}

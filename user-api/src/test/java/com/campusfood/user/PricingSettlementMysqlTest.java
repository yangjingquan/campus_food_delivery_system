package com.campusfood.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.campusfood.domain.PricingRules.Rule;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Set PRICING_IT_JDBC, PRICING_IT_USER and PRICING_IT_PASSWORD for an isolated campus_pricing_it database. */
@EnabledIfEnvironmentVariable(named = "PRICING_IT_JDBC", matches = "jdbc:mysql://(?:127\\.0\\.0\\.1|localhost):[0-9]+/campus_pricing_it(?:\\?.*)?")
class PricingSettlementMysqlTest {
    private static JdbcTemplate db;
    private static DataSourceTransactionManager manager;
    private static final long AGED = System.currentTimeMillis() - Duration.ofHours(25).toMillis();

    @BeforeAll
    static void migrate() {
        var datasource = new DriverManagerDataSource(System.getenv("PRICING_IT_JDBC"),
            System.getenv("PRICING_IT_USER"), System.getenv("PRICING_IT_PASSWORD"));
        Flyway.configure().dataSource(datasource).locations("filesystem:" +
            Path.of("../admin-api/src/main/resources/db/migration").toAbsolutePath()).load().migrate();
        Flyway.configure().dataSource(datasource).locations("classpath:db/migration")
            .table("flyway_user_api_schema_history").baselineOnMigrate(true).baselineVersion("0").load().migrate();
        for (String module : List.of("merchant", "rider")) {
            Flyway.configure().dataSource(datasource).locations("filesystem:" +
                Path.of("../" + module + "-api/src/main/resources/db/migration").toAbsolutePath())
                .table("flyway_" + module + "_api_schema_history").baselineOnMigrate(true).baselineVersion("0").load().migrate();
        }
        db = new JdbcTemplate(datasource);
        manager = new DataSourceTransactionManager(datasource);
    }

    @Test
    void checkoutUsesEachMerchantsSwitchAndLocksRatesAcrossPaymentAndSettlement() {
        scenario(f -> {
            setPricing("M03", true, "PERCENTAGE", 1255, "FIXED", 250, "FIXED", 199);
            setPricing("M04", false, "FIXED", 900, "FIXED", 0, "FIXED", 0);
            var catalog = new CatalogService(db, mock(WechatConfigurationService.class)).catalog("", "", "");
            assertEquals(1255, ((Rule) shop(catalog, "M03").get("deliveryFeeRule")).value());
            assertEquals(400, ((Rule) shop(catalog, "M04").get("deliveryFeeRule")).value());
            var basket = List.of(line("P-M03-001"), line("P-M04-001"));
            var payment = f.checkout("mixed_key", basket, false, 4251);
            var a = order(payment, "M03");
            var b = order(payment, "M04");
            assertEquals(251, number(a, "deliveryFeeCents"));
            assertEquals(400, number(b, "deliveryFeeCents"));
            newDefault(2500, 100, 100);
            setPricing("M03", false, "FIXED", 900, "FIXED", 0, "FIXED", 0);
            setPricing("M04", true, "FIXED", 77, "FIXED", 140, "FIXED", 1000);
            assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> f.checkout("new_quote", basket, false, 4251)).getStatusCode().value());
            var next = f.checkout("new_quote", basket, false, 4177);
            assertEquals(500, number(order(next, "M03"), "deliveryFeeCents"));
            assertEquals(77, number(order(next, "M04"), "deliveryFeeCents"));
            assertEquals(paymentId(payment), paymentId(f.checkout("mixed_key", basket, false, 4251)));
            f.pay(payment);
            f.fulfill(id(a), "M03", true);
            f.fulfill(id(b), "M04", true);
            f.scan();
            assertEquals(0, snapshots(id(a)).size());
            f.asRealPayment(payment);
            f.scan();
            f.scan();
            assertEquals(1750, amount(id(a), "settlement_credit"));
            assertEquals(52, amount(id(a), "rider_settlement_credit"));
            assertEquals(1440, amount(id(b), "settlement_credit"));
            assertEquals(320, amount(id(b), "rider_settlement_credit"));
            assertEquals("MERCHANT", snapshots(id(a)).getFirst().get("rule_source"));
            assertEquals("DEFAULT", snapshots(id(b)).getFirst().get("rule_source"));
            assertEquals(1L, number(snapshots(id(a)).getFirst(), "rule_version"));
            assertEquals(2, snapshots(id(a)).size());
            assertEquals(372, balance("R-PRICING"));
            f.pay(next);
            String capped = id(order(next, "M04"));
            f.fulfill(capped, "M04", true);
            f.asRealPayment(next);
            f.scan();
            assertEquals(0, amount(capped, "rider_settlement_credit"));
            assertEquals(77, number(snapshots(capped).getFirst(), "delivery_commission_cents"));
        });
    }

    @Test
    void partialRefundBeforePickupReturnsDeliveryOnlyOnceAndCapsFixedFoodCommission() {
        scenario(f -> {
            setPricing("M03", true, "FIXED", 400, "FIXED", 1700, "FIXED", 50);
            var payment = f.single(false, 2400);
            var id = id(order(payment, "M03"));
            f.pay(payment);
            f.delivery.merchantStatus("M03", id, "accept");
            assertEquals(400, number(f.orders.detail(f.user, id), "partialDeliveryRefundCents"));
            var first = f.refund(payment, id, "partial", 500);
            assertEquals(900, number(first, "amountCents"));
            assertEquals(400, number(first, "deliveryRefundCents"));
            assertEquals(500, number(first, "foodRefundCents"));
            var second = f.refund(payment, id, "partial", 250);
            assertEquals(250, number(second, "amountCents"));
            assertEquals(0, number(second, "deliveryRefundCents"));
            f.asRealPayment(payment);
            f.scan();
            assertTrue(snapshots(id).isEmpty());
            ageRefund(id);
            f.scan();
            f.scan();
            assertEquals(1, snapshots(id).size());
            var snapshot = snapshots(id).getFirst();
            assertEquals(1250, number(snapshot, "food_base_cents"));
            assertEquals(750, number(snapshot, "food_refunded_cents"));
            assertEquals(1250, number(snapshot, "food_commission_cents"));
            assertEquals(400, number(snapshot, "delivery_refunded_cents"));
            assertEquals(0, number(snapshot, "amount_cents"));
            assertEquals(409, assertThrows(ResponseStatusException.class, () ->
                f.refund(payment, id, "full", 0)).getStatusCode().value());
        });
    }

    @Test
    void partialRefundAfterPickupKeepsDeliveryAndRoundsFoodCommissionOnRemainder() {
        scenario(f -> {
            setPricing("M03", true, "FIXED", 400, "PERCENTAGE", 1250, "PERCENTAGE", 3000);
            var payment = f.single(false, 2400);
            var id = id(order(payment, "M03"));
            f.pay(payment);
            f.fulfill(id, "M03", false);
            assertEquals(0, number(f.orders.detail(f.user, id), "partialDeliveryRefundCents"));
            var refund = f.refund(payment, id, "partial", 700);
            assertEquals(700, number(refund, "amountCents"));
            assertEquals(0, number(refund, "deliveryRefundCents"));
            ageRefund(id);
            f.asRealPayment(payment);
            f.scan();
            assertEquals(1137, amount(id, "settlement_credit"));
            assertEquals(280, amount(id, "rider_settlement_credit"));
            assertEquals(163, number(snapshots(id).getFirst(), "food_commission_cents"));
            assertEquals(120, number(snapshots(id).getFirst(), "delivery_commission_cents"));
        });
    }

    @Test
    void fullRefundAfterPickupSnapshotsMerchantDebitPlatformAdvanceAndRiderCredit() {
        scenario(f -> {
            setPricing("M03", true, "FIXED", 400, "FIXED", 350, "FIXED", 150);
            var payment = f.single(false, 2400);
            var id = id(order(payment, "M03"));
            f.pay(payment);
            f.fulfill(id, "M03", false);
            db.update("UPDATE finance_account SET balance_cents=100 WHERE id='M03'");
            var refund = f.refund(payment, id, "full", 0);
            assertEquals(2400, number(refund, "amountCents"));
            assertEquals(400, number(refund, "deliveryRefundCents"));
            ageRefund(id);
            f.asRealPayment(payment);
            f.scan();
            f.scan();
            assertEquals(3, snapshots(id).size());
            assertEquals(-100, amount(id, "rider_refund_debit"));
            assertEquals(-150, amount(id, "rider_refund_advance"));
            assertEquals(250, amount(id, "rider_settlement_credit"));
            for (var snapshot : snapshots(id)) {
                assertEquals(0, number(snapshot, "food_commission_cents"));
                assertEquals(0, number(snapshot, "delivery_commission_cents"));
                assertEquals(150, number(snapshot, "rider_calculation_commission_cents"));
            }
            assertEquals(0, balance("M03"));
            assertEquals(250, balance("R-PRICING"));
            assertEquals(150L, db.queryForObject("SELECT platform_advance_cents FROM finance_debt WHERE order_id=?", Long.class, id));
        });
    }

    @Test
    void pickupIgnoresDeliveryAndLegacyOrderUsesItsOriginalConfiguration() {
        scenario(f -> {
            setPricing("M03", true, "FIXED", 950, "FIXED", 600, "FIXED", 800);
            var pickup = f.single(true, 2000);
            var pickupId = id(order(pickup, "M03"));
            f.pay(pickup);
            f.delivery.merchantStatus("M03", pickupId, "accept");
            f.delivery.merchantStatus("M03", pickupId, "ready");
            f.delivery.merchantStatus("M03", pickupId, "collect");
            ageCompletion(pickupId);
            f.asRealPayment(pickup);

            setPricing("M03", false, "FIXED", 950, "FIXED", 600, "FIXED", 800);
            var legacy = f.checkout("legacy_test", List.of(line("P-M03-001")), false, 2400);
            var legacyId = id(order(legacy, "M03"));
            db.update("DELETE FROM order_pricing_rule WHERE order_id=?", legacyId);
            newDefault(1000, 123, 200);
            f.pay(legacy);
            f.fulfill(legacyId, "M03", true);
            f.asRealPayment(legacy);

            f.scan();
            assertEquals(1, snapshots(pickupId).size());
            assertEquals(1400, amount(pickupId, "settlement_credit"));
            assertEquals(0, number(snapshots(pickupId).getFirst(), "original_delivery_cents"));
            assertEquals(1800, amount(legacyId, "settlement_credit"));
            assertEquals(320, amount(legacyId, "rider_settlement_credit"));
            assertEquals("LEGACY", snapshots(legacyId).getFirst().get("rule_source"));
        });
    }

    @Test
    void negotiatedRefundRetryUsesOriginalFoodAmountAndFullRefundDoesNotReturnDeliveryTwice() {
        scenario(f -> {
            var payment = f.single(false, 2400);
            var id = id(order(payment, "M03"));
            f.pay(payment);
            f.delivery.merchantStatus("M03", id, "accept");
            var body = Map.<String, Object>of("action", "negotiate", "type", "partial", "amountCents", 500,
                "requestKey", "refund_test_once", "reason", "收取餐品差价", "restoreStock", false);
            var first = f.refunds.merchantOperation("M03", id, body);
            assertEquals(900, number(first, "amountCents"));
            assertEquals(id(first), id(f.refunds.merchantOperation("M03", id, body)));
            var full = f.refund(payment, id, "full", 0);
            assertEquals(1500, number(full, "amountCents"));
            assertEquals(0, number(full, "deliveryRefundCents"));
            ageRefund(id);
            f.asRealPayment(payment);
            f.scan();
            assertTrue(snapshots(id).isEmpty());
        });
    }

    @Test
    void legacyMerchantWithMissingOriginalPricingDoesNotReceiveAnEstimatedCredit() {
        scenario(f -> {
            var payment = f.single(true, 2000);
            var id = id(order(payment, "M03"));
            db.update("DELETE FROM order_pricing_rule WHERE order_id=?", id);
            db.update("UPDATE user_order_detail SET food_rate_snapshot=NULL,operations_rule_id=NULL WHERE order_id=?", id);
            f.pay(payment);
            f.delivery.merchantStatus("M03", id, "accept");
            f.delivery.merchantStatus("M03", id, "ready");
            f.delivery.merchantStatus("M03", id, "collect");
            ageCompletion(id);
            f.asRealPayment(payment);
            f.scan();
            assertTrue(snapshots(id).isEmpty());
            assertEquals(0, balance("M03"));
        });
    }

    private static void scenario(Consumer<Fixture> run) {
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            run.accept(new Fixture());
            status.setRollbackOnly();
        });
    }

    private static final class Fixture {
        final long user;
        final String address;
        final OrderService orders = new OrderService(db, new AddressService(db));
        final RefundService refunds = new RefundService(db);
        final DeliveryService delivery = new DeliveryService(db, new DeliveryBatches(db));

        Fixture() {
            new DemoCatalogSeeder(db).seedWhenAdminSchemaIsReady();
            db.update("INSERT INTO user_profile(openid) VALUES('pricing-integration')");
            user = db.queryForObject("SELECT id FROM user_profile WHERE openid='pricing-integration'", Long.class);
            address = new AddressService(db).create(user, Map.of("name", "同学", "phone", "13800000000",
                "building", "5号楼", "room", "302", "latitude", 31.2301, "longitude", 121.4738)).get("id").toString();
            db.update("""
                INSERT INTO rider(id,name,phone,gender,available_time,audit_status,joined_at)
                VALUES('R-PRICING','测试骑手','13800000001','UNDISCLOSED','全时','APPROVED','2026-01-01')
                """);
            db.update("""
                INSERT INTO finance_account(id,name,type,status)
                VALUES('M03','测试商户甲','MERCHANT','ACCOUNT_ACTIVE'),('M04','测试商户乙','MERCHANT','ACCOUNT_ACTIVE'),
                      ('R-PRICING','测试骑手','RIDER','ACCOUNT_ACTIVE')
                """);
            ReflectionTestUtils.setField(orders, "paymentMode", "mock");
            ReflectionTestUtils.setField(orders, "unpaidTimeoutMinutes", 30);
            ReflectionTestUtils.setField(refunds, "paymentMode", "mock");
        }

        Map<String, Object> single(boolean pickup, int expected) {
            return checkout("single_test", List.of(line("P-M03-001")), pickup, expected);
        }

        Map<String, Object> checkout(String key, List<Map<String, Object>> items, boolean pickup, int expected) {
            return orders.checkout(user, Map.of("requestKey", key, "items", items, "expectedTotalCents", expected,
                "fulfillmentType", pickup ? "pickup" : "delivery", "addressId", address));
        }

        void pay(Map<String, Object> payment) {
            orders.mockPayment(user, paymentId(payment));
        }

        void fulfill(String order, String merchant, boolean completed) {
            delivery.merchantStatus(merchant, order, "accept");
            delivery.merchantStatus(merchant, order, "arrive");
            delivery.pickup(order, "R-PRICING");
            if (completed) {
                delivery.updateStatus(order, Map.of("status", "COMPLETED", "actor", "RIDER_DELIVERY"));
                ageCompletion(order);
            }
        }

        Map<String, Object> refund(Map<String, Object> payment, String order, String type, int foodCents) {
            db.update("UPDATE payment_order SET last_refund_at=0 WHERE id=?", paymentId(payment));
            var applied = refunds.apply(user, order, Map.of("type", type, "amountCents", foodCents, "reason", "退款自测"));
            return refunds.merchantDecision(id(applied), "approve", "");
        }

        void asRealPayment(Map<String, Object> payment) {
            String id = paymentId(payment);
            db.update("UPDATE payment_order SET payment_mode='wechat' WHERE id=?", id);
            db.update("UPDATE business_order SET payment_status='PAYMENT_SUCCEEDED' WHERE payment_id=?", id);
        }

        void scan() {
            new MerchantSettlement(db, manager).scan();
            new RiderSettlement(db, manager).scan();
        }
    }

    private static Map<String, Object> line(String id) {
        return Map.of("productId", id, "quantity", 1);
    }

    private static String paymentId(Map<String, Object> response) {
        return ((Map<?, ?>) response.get("payment")).get("id").toString();
    }

    private static String id(Map<?, ?> row) {
        return row.get("id").toString();
    }

    private static Map<?, ?> order(Map<String, Object> response, String merchant) {
        return ((List<?>) response.get("merchantOrders")).stream().map(Map.class::cast)
            .filter(row -> merchant.equals(row.get("merchantId"))).findFirst().orElseThrow();
    }

    private static Map<String, Object> shop(List<Map<String, Object>> catalog, String merchant) {
        return catalog.stream().filter(row -> merchant.equals(row.get("id"))).findFirst().orElseThrow();
    }

    private static void setPricing(String merchant, boolean enabled, String feeMode, int fee, String foodMode,
                                   int food, String deliveryMode, int delivery) {
        if (db.update("UPDATE merchant_pricing_rule SET enabled=?,version=version+1 WHERE merchant_id=?", enabled, merchant) == 0) {
            db.update("""
                INSERT INTO merchant_pricing_rule(merchant_id,version,enabled,fee_mode,fee_value,food_mode,food_value,
                    delivery_mode,delivery_value,updated_by) VALUES(?,1,?,?,?,?,?,?,?,'integration')
                """, merchant, enabled, feeMode, fee, foodMode, food, deliveryMode, delivery);
        } else {
            db.update("""
                UPDATE merchant_pricing_rule SET fee_mode=?,fee_value=?,food_mode=?,food_value=?,delivery_mode=?,
                    delivery_value=? WHERE merchant_id=?
                """, feeMode, fee, foodMode, food, deliveryMode, delivery, merchant);
        }
    }

    private static void newDefault(int feeBps, int foodFixed, int deliveryFixed) {
        db.update("""
            INSERT INTO operations_config(fee_cents,fee_bps,food_rate,food_fixed_cents,delivery_rate,
                delivery_fixed_cents,pricing_versioned,regular_batch,peak_batch,service_start,service_end,
                peak_start,peak_end,accept_timeout,reminder_interval,operator)
            VALUES(0,?,0,?,0,?,TRUE,30,20,'10:30','20:30','11:30','13:30',30,15,'integration')
            """, feeBps, foodFixed, deliveryFixed);
    }

    private static void ageRefund(String order) {
        db.update("UPDATE business_order SET last_refund_at=? WHERE id=?", AGED, order);
    }

    private static void ageCompletion(String order) {
        db.update("UPDATE business_order SET completed_at=? WHERE id=?", AGED, order);
    }

    private static List<Map<String, Object>> snapshots(String order) {
        return db.queryForList("SELECT * FROM order_settlement_snapshot WHERE order_id=? ORDER BY event_type", order);
    }

    private static long amount(String order, String event) {
        return db.queryForObject("SELECT amount_cents FROM order_settlement_snapshot WHERE order_id=? AND event_type=?",
            Long.class, order, event);
    }

    private static long balance(String account) {
        return db.queryForObject("SELECT balance_cents FROM finance_account WHERE id=?", Long.class, account);
    }

    private static long number(Map<?, ?> row, String key) {
        return ((Number) row.get(key)).longValue();
    }
}

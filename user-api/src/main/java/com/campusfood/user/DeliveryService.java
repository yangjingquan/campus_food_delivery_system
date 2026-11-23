package com.campusfood.user;

import com.campusfood.domain.BusinessStates.Fulfillment;
import com.campusfood.domain.BusinessStates.OrderActor;

import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class DeliveryService {
    private static final ZoneId CAMPUS_ZONE = ZoneId.of("Asia/Shanghai");
    private final JdbcTemplate db;
    private final DeliveryBatches batches;
    @Value("${app.internal-api-key:}") private String internalApiKey;
    @Value("${app.location-expiry-seconds:60}") private int expirySeconds;

    public DeliveryService(JdbcTemplate db, DeliveryBatches batches) {
        this.db = db;
        this.batches = batches;
    }

    public Map<String, Object> userLocation(long userId, String orderId) {
        List<Map<String, Object>> orders = db.queryForList("SELECT o.fulfill_status AS status,o.fulfillment_type,d.user_id FROM business_order o JOIN user_order_detail d ON d.order_id=o.id WHERE o.id=?", orderId);
        if (orders.isEmpty() || ((Number) orders.get(0).get("user_id")).longValue() != userId) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "订单不存在");
        if ("pickup".equals(orders.getFirst().get("fulfillment_type"))) {
            return Map.of("state", "ended", "available", false, "message", "到店自取订单没有配送位置");
        }
        String status = orders.getFirst().get("status").toString();
        if (!Fulfillment.DELIVERING.is(status)) return Map.of("state", terminal(status) ? "ended" : "waiting", "available", false, "message", terminal(status) ? "订单已结束，位置共享已关闭" : "骑手领取后将开始位置共享");
        List<Map<String, Object>> locations = db.queryForList("SELECT latitude,longitude,accuracy_m AS accuracyM,rider_name AS riderName,captured_at AS capturedAt FROM delivery_location WHERE order_id=?", orderId);
        if (locations.isEmpty()) return Map.of("state", "waiting", "available", false, "message", "骑手已领取，正在等待首次定位");
        Map<String, Object> location = locations.get(0);
        long captured = ((Number) location.get("capturedAt")).longValue();
        boolean fresh = System.currentTimeMillis() - captured <= expirySeconds * 1000L;
        location.put("state", fresh ? "live" : "stale");
        location.put("available", fresh);
        location.put("message", fresh ? "骑手正在配送" : "位置已过期，以下为最后已知位置");
        location.put("expirySeconds", expirySeconds);
        return location;
    }

    public Map<String, Object> merchantLocation(String merchant, String orderId) {
        var owners = db.queryForList("""
            SELECT d.user_id FROM business_order o JOIN user_order_detail d ON d.order_id=o.id
            WHERE o.id=? AND o.merchant_id=? AND o.paid_at>0
            """, Long.class, orderId, merchant);
        if (owners.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "订单不存在");
        return userLocation(owners.getFirst(), orderId);
    }

    @Transactional
    public Map<String, Object> merchantStatus(String merchant, String orderId, String action) {
        var orders = db.queryForList("""
            SELECT o.fulfill_status,o.fulfillment_type,o.paid_at,o.pending_refund,o.refund_status
            FROM business_order o JOIN user_order_detail d ON d.order_id=o.id
            WHERE o.id=? AND o.merchant_id=? AND o.paid_at>0 FOR UPDATE
            """, orderId, merchant);
        if (orders.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "订单不存在");
        Map<String, Object> order = orders.getFirst();
        boolean pickup = "pickup".equals(order.get("fulfillment_type"));
        if ((pickup && "arrive".equals(action))
                || (!pickup && List.of("ready", "collect").contains(action))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该操作不适用于当前取餐方式");
        }
        Fulfillment next = switch (action) {
            case "accept" -> Fulfillment.PREPARING_FOOD;
            case "arrive" -> Fulfillment.AWAITING_RIDER;
            case "ready" -> Fulfillment.AWAITING_PICKUP;
            case "collect" -> Fulfillment.COMPLETED;
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "未知履约操作");
        };
        if (next.is(order.get("fulfill_status"))) return Map.of("orderId", orderId, "status", next);
        if (Boolean.TRUE.equals(order.get("pending_refund"))
                || List.of("REFUND_FAILED", "PLATFORM_REVIEW").contains(order.get("refund_status"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "订单售后尚待处理");
        }
        if ("accept".equals(action)) {
            Integer timeout = db.queryForObject("SELECT accept_timeout FROM operations_config ORDER BY id DESC LIMIT 1", Integer.class);
            long paidAt = ((Number) order.get("paid_at")).longValue();
            if (System.currentTimeMillis() >= paidAt + timeout * 60_000L) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "已超过接单时限");
            }
        }
        return updateStatus(orderId, Map.of("status", next.name(), "actor", OrderActor.MERCHANT.name()));
    }

    @Transactional
    public Map<String, Object> pickup(String token, String riderId) {
        return pickup(token, riderId, false);
    }

    @Transactional
    public Map<String, Object> pickup(String token, String riderId, boolean rejectDuplicate) {
        String code = token == null ? "" : token.trim();
        if (!code.matches("[A-Za-z0-9_-]{1,64}") || riderId == null || riderId.isBlank() || riderId.length() > 32) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "领取码或骑手编号无效");
        }
        var riders = db.queryForList("SELECT name FROM rider WHERE id=? AND audit_status='APPROVED' AND enabled=TRUE FOR UPDATE",
            String.class, riderId);
        if (riders.isEmpty()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "骑手未通过审核或已停用");

        // 小票支持订单号二维码，也兼容历史随机领取码；两种入口统一锁定同一笔订单。
        var matches = db.queryForList("""
            SELECT id FROM business_order WHERE id=?
            UNION
            SELECT order_id AS id FROM merchant_pickup_ticket WHERE token=?
            """, String.class, code, code);
        if (matches.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "订单号或领取码无效");
        if (matches.size() != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "二维码对应多个订单，请联系运营核对");
        }
        var orders = db.queryForList("""
            SELECT o.id,o.fulfill_status,o.fulfillment_type,o.pending_refund,o.locking,o.refund_status,d.pickup_rider_id,
                o.delivery_cents
            FROM business_order o JOIN user_order_detail d ON d.order_id=o.id
            WHERE o.id=? FOR UPDATE
            """, matches.getFirst());
        if (orders.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "配送订单不存在");
        Map<String, Object> order = orders.getFirst();
        String id = order.get("id").toString();
        if ("pickup".equals(order.get("fulfillment_type"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "到店自取订单无需骑手领取");
        }
        if (Fulfillment.CLOSED.is(order.get("fulfill_status"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该订单已关闭");
        }
        if (order.get("pickup_rider_id") != null) {
            boolean own = riderId.equals(order.get("pickup_rider_id"));
            if (own && !rejectDuplicate && Fulfillment.DELIVERING.is(order.get("fulfill_status"))) {
                return Map.of("orderId", id, "status", "DELIVERING");
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT, own ? "您已领取该订单" : "该订单已被其他骑手领取");
        }
        if (!Fulfillment.AWAITING_RIDER.is(order.get("fulfill_status"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "餐品尚未到达暂存点");
        }
        if (Boolean.TRUE.equals(order.get("pending_refund")) || Boolean.TRUE.equals(order.get("locking"))
                || List.of("REFUND_FAILED", "PLATFORM_REVIEW").contains(order.get("refund_status"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "订单售后尚待处理");
        }
        var result = updateStatus(id, Map.of("status", Fulfillment.DELIVERING.name(), "riderName", riders.getFirst(), "actor", OrderActor.RIDER_SCAN.name()));
        long commission = OrderPricing.applied(db, id).rules().delivery()
            .commission(((Number) order.get("delivery_cents")).longValue());
        db.update("""
            UPDATE user_order_detail SET pickup_rider_id=?,picked_up_at=?,rider_commission_cents=? WHERE order_id=?
            """, riderId, result.get("updatedAt"), commission, id);
        return result;
    }


    @Transactional
    public Map<String, Object> riderComplete(String riderId, String orderId) {
        var order = riderOrderForUpdate(riderId, orderId);
        if (Fulfillment.COMPLETED.is(order.get("fulfill_status"))) {
            return Map.of("orderId", orderId, "status", "COMPLETED");
        }
        var result = updateStatus(orderId, Map.of("status", Fulfillment.COMPLETED.name(), "actor", OrderActor.RIDER_DELIVERY.name()));
        db.update("UPDATE rider SET deliveries=deliveries+1 WHERE id=?", riderId);
        return result;
    }

    @Transactional
    public Map<String, Object> riderLocation(String riderId, String orderId, Map<String, Object> body) {
        var order = riderOrderForUpdate(riderId, orderId);
        var location = new java.util.HashMap<>(body);
        location.put("riderName", order.get("name"));
        long captured;
        try {
            captured = Long.parseLong(text(body, "capturedAt"));
        } catch (NumberFormatException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "定位采集时间无效");
        }
        long now = System.currentTimeMillis();
        if (captured < now - expirySeconds * 1000L || captured > now + 10_000L) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "定位已过期，请重新采集");
        }
        return saveLocation(orderId, location, order, Math.min(now, captured));
    }

    private Map<String, Object> riderOrderForUpdate(String riderId, String orderId) {
        var orders = db.queryForList("""
            SELECT o.fulfill_status,r.name FROM business_order o JOIN user_order_detail d ON d.order_id=o.id
            JOIN rider r ON r.id=d.pickup_rider_id AND r.audit_status='APPROVED'
            WHERE o.id=? AND d.pickup_rider_id=? FOR UPDATE
            """, orderId, riderId);
        if (orders.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "配送订单不存在");
        }
        return orders.getFirst();
    }

    @Transactional
    public Map<String, Object> updateStatus(String orderId, Map<String, Object> body) {
        Map<String, Object> current = orderForUpdate(orderId);
        Fulfillment previous = Fulfillment.valueOf(current.get("fulfill_status").toString());
        Fulfillment next;
        OrderActor actor;
        try {
            next = Fulfillment.valueOf(text(body, "status"));
            String actorCode = text(body, "actor");
            actor = actorCode.isBlank() ? OrderActor.FULFILLMENT_SERVICE : OrderActor.valueOf(actorCode);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "履约状态或操作来源无效");
        }
        boolean pickup = "pickup".equals(current.get("fulfillment_type"));
        boolean valid = switch (previous) {
            case AWAITING_MERCHANT -> next == Fulfillment.PREPARING_FOOD;
            case PREPARING_FOOD -> next == (pickup ? Fulfillment.AWAITING_PICKUP : Fulfillment.AWAITING_RIDER);
            case AWAITING_PICKUP -> pickup && next == Fulfillment.COMPLETED;
            case AWAITING_RIDER -> !pickup && next == Fulfillment.DELIVERING;
            case DELIVERING -> !pickup && next == Fulfillment.COMPLETED;
            default -> false;
        };
        if (!valid) throw new ResponseStatusException(HttpStatus.CONFLICT, "订单当前状态不允许此配送流转");
        long now = System.currentTimeMillis();
        if (next == Fulfillment.PREPARING_FOOD) {
            acceptWithPickupNumber(orderId, current.get("merchant_id").toString(), pickup ? "pickup" : "delivery", now);
        } else if (next == Fulfillment.DELIVERING) {
            String rider = text(body, "riderName");
            if (rider.isBlank() || rider.length() > 120) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "骑手名称无效");
            db.update("""
                UPDATE business_order SET fulfill_status=?,rider=?,
                    intervene=IF(intervene_code='PICKUP_OVERDUE',FALSE,intervene),
                    intervene_code=IF(intervene_code='PICKUP_OVERDUE',NULL,intervene_code)
                WHERE id=?
                """, next.name(), rider, orderId);
        } else if (next == Fulfillment.COMPLETED) {
            db.update("UPDATE business_order SET fulfill_status=?,completed_at=?,settlement_status='PENDING_SETTLEMENT' WHERE id=?", next.name(), now, orderId);
            db.update("DELETE FROM delivery_location WHERE order_id=?", orderId);
        } else {
            db.update("UPDATE business_order SET fulfill_status=? WHERE id=?", next.name(), orderId);
            if (!pickup) batches.recordArrival(orderId, now);
        }
        db.update("""
            INSERT INTO order_note(order_id,actor,note,actor_code,previous_status,next_status)
            VALUES(?,'','',?,?,?)
            """, orderId, actor.name(), previous.name(), next.name());
        return Map.of("orderId", orderId, "previousStatus", previous, "status", next, "updatedAt", now);
    }

    private void acceptWithPickupNumber(String orderId, String merchantId, String fulfillmentType, long acceptedAt) {
        var date = Instant.ofEpochMilli(acceptedAt).atZone(CAMPUS_ZONE).toLocalDate();
        // 计数行锁随接单事务持有，确保同店并发接单不重号，接单回滚时计数也回滚。
        db.update("""
            INSERT INTO merchant_daily_pickup_sequence (merchant_id,pickup_date,fulfillment_type,last_number) VALUES (?,?,?,1)
            ON DUPLICATE KEY UPDATE last_number=last_number+1
            """, merchantId, date, fulfillmentType);
        Long number = db.queryForObject("""
            SELECT last_number FROM merchant_daily_pickup_sequence WHERE merchant_id=? AND pickup_date=? AND fulfillment_type=?
            """, Long.class, merchantId, date, fulfillmentType);
        db.update("""
            UPDATE business_order SET fulfill_status='PREPARING_FOOD',accepted=TRUE,pickup_date=?,pickup_number=? WHERE id=?
            """, date, number, orderId);
    }

    @Transactional
    public Map<String, Object> updateLocation(String orderId, Map<String, Object> body) {
        return saveLocation(orderId, body, orderForUpdate(orderId), System.currentTimeMillis());
    }

    private Map<String, Object> saveLocation(String orderId, Map<String, Object> body,
                                             Map<String, Object> order, long captured) {
        if (!Fulfillment.DELIVERING.is(order.get("fulfill_status"))) throw new ResponseStatusException(HttpStatus.CONFLICT, "订单未处于配送中，不能共享位置");
        double latitude = decimal(body.get("latitude"), -90, 90, "纬度");
        double longitude = decimal(body.get("longitude"), -180, 180, "经度");
        double accuracy = body.get("accuracyM") == null ? 0 : decimal(body.get("accuracyM"), 0, 10000, "定位精度");
        String rider = text(body, "riderName");
        db.update("""
            INSERT INTO delivery_location(order_id,latitude,longitude,accuracy_m,rider_name,captured_at)
            VALUES(?,?,?,?,?,?) AS sample
            ON DUPLICATE KEY UPDATE
                latitude=IF(sample.captured_at>=delivery_location.captured_at,sample.latitude,delivery_location.latitude),
                longitude=IF(sample.captured_at>=delivery_location.captured_at,sample.longitude,delivery_location.longitude),
                accuracy_m=IF(sample.captured_at>=delivery_location.captured_at,sample.accuracy_m,delivery_location.accuracy_m),
                rider_name=IF(sample.captured_at>=delivery_location.captured_at,sample.rider_name,delivery_location.rider_name),
                captured_at=GREATEST(sample.captured_at,delivery_location.captured_at)
            """, orderId, latitude, longitude, accuracy, rider, captured);
        return Map.of("orderId", orderId, "capturedAt", captured, "accepted", true);
    }

    public void requireInternal(HttpServletRequest request) {
        String supplied = request.getHeader("X-Internal-Key");
        if (internalApiKey == null || internalApiKey.isBlank()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "内部服务密钥尚未配置");
        if (supplied == null || !MessageDigest.isEqual(internalApiKey.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "内部调用未授权");
        }
    }

    private Map<String, Object> orderForUpdate(String orderId) {
        List<Map<String, Object>> rows = db.queryForList("SELECT id,merchant_id,fulfill_status,fulfillment_type FROM business_order WHERE id=? FOR UPDATE", orderId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "订单不存在");
        return rows.get(0);
    }
    private static boolean terminal(String status) { return Fulfillment.COMPLETED.is(status) || Fulfillment.CLOSED.is(status); }
    private static double decimal(Object value, double min, double max, String label) {
        try {
            double number = new BigDecimal(String.valueOf(value)).doubleValue();
            if (Double.isFinite(number) && number >= min && number <= max) return number;
        } catch (Exception ignored) { }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + "无效");
    }
    private static String text(Map<String, Object> body, String key) { return body.get(key) == null ? "" : body.get(key).toString().trim(); }
}

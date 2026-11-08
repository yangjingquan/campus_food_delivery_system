package com.campusfood.user;

import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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
    private final JdbcTemplate db;
    private final DeliveryBatches batches;
    @Value("${app.internal-api-key:}") private String internalApiKey;
    @Value("${app.location-expiry-seconds:60}") private int expirySeconds;

    public DeliveryService(JdbcTemplate db, DeliveryBatches batches) {
        this.db = db;
        this.batches = batches;
    }

    public Map<String, Object> userLocation(long userId, String orderId) {
        List<Map<String, Object>> orders = db.queryForList("SELECT o.fulfill_status AS status,d.user_id FROM business_order o JOIN user_order_detail d ON d.order_id=o.id WHERE o.id=?", orderId);
        if (orders.isEmpty() || ((Number) orders.get(0).get("user_id")).longValue() != userId) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "订单不存在");
        String status = orders.get(0).get("status").toString();
        if (!"配送中".equals(status)) return Map.of("state", terminal(status) ? "ended" : "waiting", "available", false, "message", terminal(status) ? "订单已结束，位置共享已关闭" : "骑手领取后将开始位置共享");
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
            SELECT o.fulfill_status,o.paid_at,o.pending_refund,o.refund_status
            FROM business_order o JOIN user_order_detail d ON d.order_id=o.id
            WHERE o.id=? AND o.merchant_id=? AND o.paid_at>0 FOR UPDATE
            """, orderId, merchant);
        if (orders.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "订单不存在");
        Map<String, Object> order = orders.getFirst();
        String next = switch (action) {
            case "accept" -> "商户出餐中";
            case "arrive" -> "已到暂存点 / 待领取";
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "未知履约操作");
        };
        if (next.equals(order.get("fulfill_status"))) return Map.of("orderId", orderId, "status", next);
        if (Boolean.TRUE.equals(order.get("pending_refund"))
                || List.of("退款失败", "平台介入").contains(order.get("refund_status"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "订单售后尚待处理");
        }
        if ("accept".equals(action)) {
            Integer timeout = db.queryForObject("SELECT accept_timeout FROM operations_config ORDER BY id DESC LIMIT 1", Integer.class);
            long paidAt = ((Number) order.get("paid_at")).longValue();
            if (System.currentTimeMillis() >= paidAt + timeout * 60_000L) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "已超过接单时限");
            }
        }
        return updateStatus(orderId, Map.of("status", next, "actor", "商户"));
    }

    @Transactional
    public Map<String, Object> pickup(String token, String riderId) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{22}") || riderId == null || riderId.length() > 32) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "领取码或骑手编号无效");
        }
        var riders = db.queryForList("SELECT name FROM rider WHERE id=? AND audit_status='已通过'",
            String.class, riderId);
        if (riders.isEmpty()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "骑手未通过审核");

        // 锁住订单而不是只锁二维码，扫码与其他履约入口使用同一行状态互斥。
        var orders = db.queryForList("""
            SELECT o.id,o.fulfill_status,o.pending_refund,o.refund_status,d.pickup_rider_id
            FROM merchant_pickup_ticket t JOIN business_order o ON o.id=t.order_id
            JOIN user_order_detail d ON d.order_id=o.id WHERE t.token=? FOR UPDATE
            """, token);
        if (orders.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "领取码无效");
        Map<String, Object> order = orders.getFirst();
        String id = order.get("id").toString();
        if ("配送中".equals(order.get("fulfill_status")) && riderId.equals(order.get("pickup_rider_id"))) {
            return Map.of("orderId", id, "status", "配送中");
        }
        if (Boolean.TRUE.equals(order.get("pending_refund"))
                || List.of("退款失败", "平台介入").contains(order.get("refund_status"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "订单售后尚待处理");
        }
        var result = updateStatus(id, Map.of("status", "配送中", "riderName", riders.getFirst(), "actor", "骑手扫码"));
        db.update("UPDATE user_order_detail SET pickup_rider_id=? WHERE order_id=?", riderId, id);
        return result;
    }

    @Transactional
    public Map<String, Object> updateStatus(String orderId, Map<String, Object> body) {
        Map<String, Object> current = orderForUpdate(orderId);
        String previous = current.get("fulfill_status").toString();
        String next = text(body, "status");
        boolean valid = switch (previous) {
            case "待商户接单" -> "商户出餐中".equals(next);
            case "商户出餐中" -> "已到暂存点 / 待领取".equals(next);
            case "已到暂存点 / 待领取" -> "配送中".equals(next);
            case "配送中" -> "已完成".equals(next);
            default -> false;
        };
        if (!valid) throw new ResponseStatusException(HttpStatus.CONFLICT, "订单当前状态不允许此配送流转");
        long now = System.currentTimeMillis();
        if ("商户出餐中".equals(next)) {
            db.update("UPDATE business_order SET fulfill_status=?,accepted=TRUE WHERE id=?", next, orderId);
        } else if ("配送中".equals(next)) {
            String rider = text(body, "riderName");
            if (rider.isBlank() || rider.length() > 120) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "骑手名称无效");
            db.update("""
                UPDATE business_order SET fulfill_status=?,rider=?,
                    intervene=IF(intervene_reason=?,FALSE,intervene),
                    intervene_reason=IF(intervene_reason=?,NULL,intervene_reason)
                WHERE id=?
                """, next, rider, DeliveryBatches.OVERDUE, DeliveryBatches.OVERDUE, orderId);
        } else if ("已完成".equals(next)) {
            db.update("UPDATE business_order SET fulfill_status=?,completed_at=?,settlement_status='待结算' WHERE id=?", next, now, orderId);
            db.update("DELETE FROM delivery_location WHERE order_id=?", orderId);
        } else {
            db.update("UPDATE business_order SET fulfill_status=? WHERE id=?", next, orderId);
            batches.recordArrival(orderId, now);
        }
        db.update("INSERT INTO order_note(order_id,actor,note) VALUES(?,?,?)", orderId, text(body, "actor").isBlank() ? "履约服务" : text(body, "actor"), previous + " → " + next);
        return Map.of("orderId", orderId, "previousStatus", previous, "status", next, "updatedAt", now);
    }

    @Transactional
    public Map<String, Object> updateLocation(String orderId, Map<String, Object> body) {
        Map<String, Object> order = orderForUpdate(orderId);
        if (!"配送中".equals(order.get("fulfill_status"))) throw new ResponseStatusException(HttpStatus.CONFLICT, "订单未处于配送中，不能共享位置");
        double latitude = decimal(body.get("latitude"), -90, 90, "纬度");
        double longitude = decimal(body.get("longitude"), -180, 180, "经度");
        double accuracy = body.get("accuracyM") == null ? 0 : decimal(body.get("accuracyM"), 0, 10000, "定位精度");
        String rider = text(body, "riderName");
        long now = System.currentTimeMillis();
        List<Map<String, Object>> existing = db.queryForList("SELECT order_id FROM delivery_location WHERE order_id=? FOR UPDATE", orderId);
        if (existing.isEmpty()) {
            db.update("INSERT INTO delivery_location(order_id,latitude,longitude,accuracy_m,rider_name,captured_at) VALUES(?,?,?,?,?,?)", orderId, latitude, longitude, accuracy, rider, now);
        } else {
            db.update("UPDATE delivery_location SET latitude=?,longitude=?,accuracy_m=?,rider_name=?,captured_at=? WHERE order_id=?", latitude, longitude, accuracy, rider, now, orderId);
        }
        return Map.of("orderId", orderId, "capturedAt", now, "accepted", true);
    }

    public void requireInternal(HttpServletRequest request) {
        String supplied = request.getHeader("X-Internal-Key");
        if (internalApiKey == null || internalApiKey.isBlank()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "内部服务密钥尚未配置");
        if (supplied == null || !MessageDigest.isEqual(internalApiKey.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "内部调用未授权");
        }
    }

    private Map<String, Object> orderForUpdate(String orderId) {
        List<Map<String, Object>> rows = db.queryForList("SELECT id,fulfill_status FROM business_order WHERE id=? FOR UPDATE", orderId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "订单不存在");
        return rows.get(0);
    }
    private static boolean terminal(String status) { return List.of("已完成", "已关闭").contains(status); }
    private static double decimal(Object value, double min, double max, String label) {
        try {
            double number = new BigDecimal(String.valueOf(value)).doubleValue();
            if (Double.isFinite(number) && number >= min && number <= max) return number;
        } catch (Exception ignored) { }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + "无效");
    }
    private static String text(Map<String, Object> body, String key) { return body.get(key) == null ? "" : body.get(key).toString().trim(); }
}

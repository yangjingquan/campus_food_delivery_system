package com.campusfood.merchant.service;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class OrderService {
    private static final String BASE = """
        SELECT o.id,o.payment_id AS paymentId,o.items,o.food_cents AS foodCents,o.delivery_cents AS deliveryCents,
               o.paid_cents AS paidCents,o.refunded_cents AS refundedCents,o.food_refunded_cents AS foodRefundedCents,
               o.fulfill_status AS status,o.payment_status AS paymentStatus,o.refund_status AS refundStatus,
               o.settlement_status AS settlementStatus,o.accepted,o.pending_refund AS pendingRefund,o.intervene,
               o.intervene_reason AS interveneReason,o.paid_at AS paidAt,d.arrived_at AS arrivedAt,
               d.batch_alert_at AS batchAlertAt,o.completed_at AS completedAt,o.closed_at AS closedAt,
               o.paid_at+(SELECT accept_timeout FROM operations_config ORDER BY id DESC LIMIT 1)*60000 AS acceptDeadlineAt
        FROM business_order o JOIN user_order_detail d ON d.order_id=o.id
        WHERE o.merchant_id=? AND o.paid_at>0
        """;

    private static final String REFUNDS = """
        SELECT r.id,r.order_id AS orderId,r.type,r.reason,r.amount_cents AS amountCents,r.status,
               r.channel_status AS channelStatus,r.channel_reason AS channelReason,r.created_at AS createdAt,
               r.offline_channel AS offlineChannel,r.offline_actual_at AS offlineActualAt
        FROM refund_record r JOIN business_order o ON o.id=r.order_id JOIN user_order_detail d ON d.order_id=o.id
        WHERE o.merchant_id=? AND o.paid_at>0
        """;

    private final JdbcTemplate db;
    private final SecureRandom random = new SecureRandom();

    public OrderService(JdbcTemplate db) {
        this.db = db;
    }

    public Map<String, Object> list(String merchant, String status, String keyword, int page) {
        String term = "%" + keyword.trim() + "%";
        int offset = Math.min(100_000, Math.max(0, page)) * 30;
        var rows = db.queryForList(BASE + """
            AND (?='' OR o.fulfill_status=?) AND (o.id LIKE ? OR o.items LIKE ?)
            ORDER BY o.paid_at DESC,o.id DESC LIMIT 31 OFFSET ?
            """, merchant, status, status, term, term, offset);
        var counts = db.queryForMap("""
            SELECT COUNT(CASE WHEN o.fulfill_status='待商户接单' AND o.pending_refund=FALSE
                          AND o.refund_status NOT IN ('退款失败','平台介入')
                          AND o.paid_at+(SELECT accept_timeout FROM operations_config ORDER BY id DESC LIMIT 1)*60000>? THEN 1 END) AS waiting,
                   COUNT(CASE WHEN o.refund_status='待商户处理' THEN 1 END) AS aftersales
            FROM business_order o JOIN user_order_detail d ON d.order_id=o.id WHERE o.merchant_id=? AND o.paid_at>0
            """, System.currentTimeMillis(), merchant);
        return Map.of("rows", rows.subList(0, Math.min(rows.size(), 30)), "hasMore", rows.size() > 30, "counts", counts);
    }

    public Map<String, Object> detail(String merchant, String id) {
        var result = new LinkedHashMap<>(owned(merchant, id));
        result.put("delivery", db.queryForMap("""
            SELECT receiver_name AS receiverName,phone,building,room FROM user_order_detail WHERE order_id=?
            """, id));
        result.put("lines", lines(id));
        result.put("aftersales", db.queryForList(REFUNDS + " AND o.id=? ORDER BY r.created_at DESC", merchant, id));
        result.put("events", db.queryForList("""
            SELECT note,created_at AS createdAt FROM order_note WHERE order_id=? ORDER BY id DESC LIMIT 100
            """, id));
        return result;
    }

    public List<Map<String, Object>> aftersales(String merchant, int page) {
        int offset = Math.min(100_000, Math.max(0, page)) * 30;
        return db.queryForList(REFUNDS + " ORDER BY r.created_at DESC,r.id DESC LIMIT 30 OFFSET ?", merchant, offset);
    }

    @Transactional
    public Map<String, Object> ticket(String merchant, String id) {
        Map<String, Object> order = owned(merchant, id);
        if (!Boolean.TRUE.equals(order.get("accepted")) || "已关闭".equals(order.get("status"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "接单后且订单未关闭时可查看小票");
        }
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        String candidate = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        // 补打复用同一随机码。二维码不包含订单号、地址或联系电话。
        db.update("INSERT INTO merchant_pickup_ticket(order_id,token) VALUES(?,?) ON DUPLICATE KEY UPDATE order_id=order_id",
            id, candidate);
        String code = db.queryForObject("SELECT token FROM merchant_pickup_ticket WHERE order_id=?", String.class, id);
        return Map.of("id", id, "lines", lines(id), "paidCents", order.get("paidCents"),
            "deliveryCents", order.get("deliveryCents"), "code", code, "qrImage", TicketQr.image(code));
    }

    private List<Map<String, Object>> lines(String id) {
        return db.queryForList("""
            SELECT name_snapshot AS name,quantity,price_cents AS priceCents,amount_cents AS amountCents
            FROM user_order_item WHERE order_id=? ORDER BY id
            """, id);
    }

    private Map<String, Object> owned(String merchant, String id) {
        return db.queryForList(BASE + " AND o.id=?", merchant, id).stream().findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "订单不存在"));
    }
}

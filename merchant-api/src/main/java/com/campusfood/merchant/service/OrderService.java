package com.campusfood.merchant.service;

import com.campusfood.domain.BusinessStates.Fulfillment;

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
    private static final int ORDER_PAGE_SIZE = 10;
    private static final String BASE = """
        SELECT o.id,o.payment_id AS paymentId,o.items,o.food_cents AS foodCents,o.delivery_cents AS deliveryCents,
               o.paid_cents AS paidCents,o.refunded_cents AS refundedCents,o.food_refunded_cents AS foodRefundedCents,
               IF(d.pickup_rider_id IS NULL,GREATEST(0,o.delivery_cents-o.refunded_cents+o.food_refunded_cents),0) AS partialDeliveryRefundCents,
               (d.rider_settled_at IS NOT NULL OR d.merchant_settled_at IS NOT NULL
                 OR o.settlement_status IN ('WITHDRAWABLE','SETTLED','WITHDRAWN')) AS settlementRecorded,
               d.merchant_settled_cents AS merchantSettledCents,
               o.fulfill_status AS status,o.payment_status AS paymentStatus,o.refund_status AS refundStatus,
               o.settlement_status AS settlementStatus,o.fulfillment_type AS fulfillmentType,
               o.pickup_number AS pickupNumber,
               CONCAT('#',IF(o.fulfillment_type='pickup','Z','W'),o.pickup_number) AS pickupCode,o.accepted,o.pending_refund AS pendingRefund,o.intervene,
               o.intervene_code AS interveneCode,o.intervene_reason AS interveneReason,o.paid_at AS paidAt,d.arrived_at AS arrivedAt,
               COALESCE(d.order_remark,'') AS remark,COALESCE(d.tableware_note,'') AS tableware,
               d.batch_alert_at AS batchAlertAt,o.completed_at AS completedAt,o.closed_at AS closedAt,
               o.paid_at+(SELECT accept_timeout FROM operations_config ORDER BY id DESC LIMIT 1)*60000 AS acceptDeadlineAt
        FROM business_order o JOIN user_order_detail d ON d.order_id=o.id
        WHERE o.merchant_id=? AND o.paid_at>0
        """;

    private static final String REFUNDS = """
        SELECT r.id,r.order_id AS orderId,r.type,r.reason,r.amount_cents AS amountCents,r.delivery_refund_cents AS deliveryRefundCents,
               r.amount_cents-r.delivery_refund_cents AS foodRefundCents,r.status,
               r.channel_status AS channelStatus,r.channel_detail AS channelDetail,r.channel_reason AS channelReason,r.created_at AS createdAt,
               r.offline_channel AS offlineChannel,r.offline_actual_at AS offlineActualAt,
               COALESCE(x.description,'') AS description,
               EXISTS(SELECT 1 FROM user_refund_photo p WHERE p.refund_id=r.id) AS hasPhoto
        FROM refund_record r JOIN business_order o ON o.id=r.order_id JOIN user_order_detail d ON d.order_id=o.id
        LEFT JOIN user_refund_detail x ON x.refund_id=r.id
        WHERE o.merchant_id=? AND o.paid_at>0
        """;

    private final JdbcTemplate db;
    private final SecureRandom random = new SecureRandom();

    public OrderService(JdbcTemplate db) {
        this.db = db;
    }

    public Map<String, Object> list(String merchant, String status, String keyword, int page) {
        if (!status.isEmpty()) {
            try {
                status = Fulfillment.valueOf(status).name();
            } catch (IllegalArgumentException e) {
                throw Inputs.invalid("履约状态无效");
            }
        }
        String term = "%" + keyword.trim() + "%";
        long offset = (long) Math.max(0, page) * ORDER_PAGE_SIZE;
        var rows = db.queryForList(BASE + """
            AND (?='' OR o.fulfill_status=?)
            AND (o.id LIKE ? OR o.items LIKE ? OR CONCAT('#',IF(o.fulfillment_type='pickup','Z','W'),o.pickup_number) LIKE ?)
            ORDER BY o.paid_at DESC,o.id DESC LIMIT ? OFFSET ?
            """, merchant, status, status, term, term, term, ORDER_PAGE_SIZE + 1, offset);
        var counts = db.queryForMap("""
            SELECT COUNT(CASE WHEN o.fulfill_status='AWAITING_MERCHANT' AND o.pending_refund=FALSE
                          AND o.refund_status NOT IN ('REFUND_FAILED','PLATFORM_REVIEW')
                          AND o.paid_at+(SELECT accept_timeout FROM operations_config ORDER BY id DESC LIMIT 1)*60000>? THEN 1 END) AS waiting,
                   COUNT(CASE WHEN o.refund_status='AWAITING_MERCHANT_DECISION' THEN 1 END) AS aftersales
            FROM business_order o JOIN user_order_detail d ON d.order_id=o.id WHERE o.merchant_id=? AND o.paid_at>0
            """, System.currentTimeMillis(), merchant);
        return Map.of("rows", rows.subList(0, Math.min(rows.size(), ORDER_PAGE_SIZE)),
            "hasMore", rows.size() > ORDER_PAGE_SIZE, "counts", counts);
    }

    public Map<String, Object> detail(String merchant, String id) {
        var result = new LinkedHashMap<>(owned(merchant, id));
        result.put("delivery", db.queryForMap("""
            SELECT receiver_name AS receiverName,phone,building,room,pickup_address AS pickupAddress FROM user_order_detail WHERE order_id=?
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

    public UploadedPhoto aftersalePhoto(String merchant, String refundId) {
        var photo = db.query("""
            SELECT p.photo_data,p.media_type
            FROM user_refund_photo p
            JOIN refund_record r ON r.id=p.refund_id
            JOIN business_order o ON o.id=r.order_id
            WHERE p.refund_id=? AND o.merchant_id=? AND o.paid_at>0
            """, rows -> rows.next() ? new UploadedPhoto(rows.getBytes("photo_data"), rows.getString("media_type")) : null,
            refundId, merchant);
        if (photo == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "售后凭证图片不存在");
        return photo;
    }

    @Transactional
    public Map<String, Object> ticket(String merchant, String id) {
        Map<String, Object> order = owned(merchant, id);
        if (!Boolean.TRUE.equals(order.get("accepted")) || Fulfillment.CLOSED.is(order.get("status"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "接单后且订单未关闭时可查看小票");
        }
        var ticket = receipt(merchant, id);
        if (!"pickup".equals(order.get("fulfillmentType"))) {
            byte[] bytes = new byte[16];
            random.nextBytes(bytes);
            String candidate = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            // 外卖补打复用原骑手领取码。自取单没有骑手领取码。
            db.update("INSERT INTO merchant_pickup_ticket(order_id,token) VALUES(?,?) ON DUPLICATE KEY UPDATE order_id=order_id",
                id, candidate);
            String code = db.queryForObject("SELECT token FROM merchant_pickup_ticket WHERE order_id=?", String.class, id);
            ticket.put("code", code);
            ticket.put("qrImage", TicketQr.image(code));
            ticket.put("orderQrImage", TicketQr.image(id));
        }
        ticket.put("createdTime", ReceiptFormatter.time(ticket.remove("createdAt")));
        return ticket;
    }

    public Map<String, Object> receipt(String merchant, String id) {
        Map<String, Object> order = owned(merchant, id);
        if (!Boolean.TRUE.equals(order.get("accepted")) || Fulfillment.CLOSED.is(order.get("status"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "接单后且订单未关闭时可打印小票");
        }
        var ticket = new LinkedHashMap<>(order);
        ticket.putAll(db.queryForMap("""
            SELECT m.name AS merchantName,
                IF(o.fulfillment_type='pickup',d.pickup_address,CONCAT(d.building,' ',d.room)) AS address,
                d.created_at AS createdAt,d.receiver_name AS receiverName,d.phone
            FROM user_order_detail d JOIN business_order o ON o.id=d.order_id
            JOIN merchant m ON m.id=o.merchant_id WHERE d.order_id=? AND o.merchant_id=?
            """, id, merchant));
        ticket.put("maskedReceiverName", ReceiptFormatter.maskName((String) ticket.remove("receiverName")));
        ticket.put("maskedPhone", ReceiptFormatter.maskPhone((String) ticket.remove("phone")));
        ticket.put("lines", lines(id));
        return ticket;
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

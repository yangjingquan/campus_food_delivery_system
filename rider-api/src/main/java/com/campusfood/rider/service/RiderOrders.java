package com.campusfood.rider.service;

import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RiderOrders {
    private static final String WAITING = "已到暂存点 / 待领取";
    private static final String OWNED = """
        SELECT o.id,o.merchant,o.items,o.id AS code,o.fulfill_status AS status,
               d.building,d.room,o.delivery_cents AS feeCents,d.rider_commission_cents AS commissionCents,
               o.delivery_cents-d.rider_commission_cents AS dueCents,o.paid_at AS paidAt,d.arrived_at AS arrivedAt,
               d.picked_up_at AS pickedUpAt,o.completed_at AS completedAt,o.closed_at AS closedAt,
               o.refund_status AS refundStatus,o.last_refund_at AS lastRefundAt,d.rider_settled_at AS settledAt,
               CASE
                   WHEN d.rider_settled_at IS NOT NULL THEN '可提现'
                   WHEN o.pending_refund OR o.locking OR o.refund_status NOT IN ('无售后','已结束','退款成功','线下已处理') THEN '售后暂缓'
                   ELSE '待结算'
               END AS incomeStatus,
               COALESCE(o.last_refund_at,o.completed_at)+86400000 AS eligibleAt
        FROM business_order o JOIN user_order_detail d ON d.order_id=o.id
        """;

    private final JdbcTemplate db;

    public RiderOrders(JdbcTemplate db) {
        this.db = db;
    }

    public List<Map<String, Object>> list(String rider, String scope, int offset) {
        String filter = switch (scope) {
            case "active" -> " AND o.fulfill_status='配送中'";
            case "history" -> " AND o.fulfill_status IN ('已完成','已关闭')";
            case "income" -> "";
            default -> throw Inputs.invalid("查询类型无效");
        };
        return db.queryForList(OWNED + " WHERE d.pickup_rider_id=?" + filter
            + " ORDER BY d.picked_up_at DESC,o.id DESC LIMIT ? OFFSET ?", rider,
            "active".equals(scope) ? Integer.MAX_VALUE : 30, Math.max(0, offset));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> detail(String rider, String id) {
        var rows = db.queryForList(OWNED + " WHERE d.pickup_rider_id=? AND o.id=?", rider, id);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "配送订单不存在");
        }
        var order = rows.getFirst();
        // 配送结束后仍可查履约与收益，收餐联系电话仅配送期间提供。
        if ("配送中".equals(order.get("status"))) {
            order.putAll(db.queryForMap("""
                SELECT d.receiver_name AS receiver,d.phone,t.latitude,t.longitude
                FROM user_order_detail d LEFT JOIN delivery_destination t ON t.building=d.building
                WHERE d.order_id=?
                """, id));
        }
        order.put("products", db.queryForList("""
            SELECT name_snapshot AS name,quantity FROM user_order_item WHERE order_id=? ORDER BY id
            """, id));
        order.put("refunds", db.queryForList("""
            SELECT status,amount_cents AS amountCents,created_at AS createdAt,channel_status AS channelStatus,
                   channel_reason AS reason,offline_actual_at AS actualAt
            FROM refund_record WHERE order_id=? ORDER BY created_at,id
            """, id));
        order.put("timeline", db.queryForList("""
            SELECT note,UNIX_TIMESTAMP(created_at)*1000 AS createdAt
            FROM order_note WHERE order_id=? AND actor IN ('商户','骑手扫码','骑手送达') ORDER BY id
            """, id));
        return order;
    }

    public Map<String, Object> batches() {
        var config = db.queryForMap("""
            SELECT service_start AS serviceStart,service_end AS serviceEnd,peak_start AS peakStart,
                   peak_end AS peakEnd,regular_batch AS regularBatch,peak_batch AS peakBatch
            FROM operations_config ORDER BY id DESC LIMIT 1
            """);
        // 未领取列表只显示领餐所需信息，隐藏寝室、用户身份和二维码凭据。
        var waiting = db.queryForList("""
            SELECT o.merchant,o.id AS code,d.arrived_at AS arrivedAt,
                   d.batch_alert_at AS alertAt
            FROM business_order o JOIN user_order_detail d ON d.order_id=o.id
            WHERE o.fulfill_status=? AND d.pickup_rider_id IS NULL AND o.pending_refund=FALSE AND o.locking=FALSE
              AND o.refund_status NOT IN ('退款失败','平台介入')
            ORDER BY d.arrived_at,o.id LIMIT 100
            """, WAITING);
        Long count = db.queryForObject("""
            SELECT COUNT(*) FROM business_order o JOIN user_order_detail d ON d.order_id=o.id
            WHERE o.fulfill_status=? AND d.pickup_rider_id IS NULL AND o.pending_refund=FALSE AND o.locking=FALSE
              AND o.refund_status NOT IN ('退款失败','平台介入')
            """, Long.class, WAITING);
        return Map.of("waiting", waiting, "count", count, "config", config, "serverTime", System.currentTimeMillis());
    }
}

package com.campusfood.rider.service;

import com.campusfood.domain.BusinessStates.Fulfillment;

import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RiderOrders {
    private static final String WAITING = Fulfillment.AWAITING_RIDER.name();
    static final String FEE = "GREATEST(0,o.delivery_cents-o.refunded_cents+o.food_refunded_cents)";
    static final String COMMISSION = "COALESCE(s.delivery_commission_cents,IF(o.refunded_cents>0 AND o.paid_cents=o.refunded_cents,0,d.rider_commission_cents))";
    static final String DUE = "COALESCE(s.amount_cents,o.delivery_cents-d.rider_commission_cents)";
    static final String SETTLEMENT_JOIN = " LEFT JOIN order_settlement_snapshot s ON s.order_id=o.id AND s.event_type='rider_settlement_credit' ";
    private static final String OWNED = """
        SELECT o.id,o.merchant,o.pickup_number AS pickupNumber,o.items,o.id AS code,o.fulfill_status AS status,
               d.building,d.room,%s AS feeCents,%s AS commissionCents,%s AS dueCents,
               IF(o.refunded_cents>0 AND o.paid_cents=o.refunded_cents,%s,0) AS fundedCents,o.paid_at AS paidAt,d.arrived_at AS arrivedAt,
               d.picked_up_at AS pickedUpAt,o.completed_at AS completedAt,o.closed_at AS closedAt,
               o.refund_status AS refundStatus,o.last_refund_at AS lastRefundAt,d.rider_settled_at AS settledAt,
               CASE
                   WHEN d.rider_settled_at IS NOT NULL THEN 'WITHDRAWABLE'
                   WHEN o.pending_refund OR o.locking OR o.refund_status NOT IN ('NO_AFTER_SALE','AFTER_SALE_ENDED','REFUND_SUCCEEDED','OFFLINE_RESOLVED') THEN 'SETTLEMENT_HELD'
                   ELSE 'PENDING_SETTLEMENT'
               END AS incomeStatus,
               COALESCE(o.last_refund_at,o.completed_at)+86400000 AS eligibleAt
        FROM business_order o JOIN user_order_detail d ON d.order_id=o.id
        """.formatted(FEE, COMMISSION, DUE, DUE) + SETTLEMENT_JOIN;

    private final JdbcTemplate db;

    public RiderOrders(JdbcTemplate db) {
        this.db = db;
    }

    public List<Map<String, Object>> list(String rider, String scope, int offset) {
        String filter = switch (scope) {
            case "active" -> " AND o.fulfill_status='DELIVERING'";
            case "history" -> " AND o.fulfill_status IN ('COMPLETED','CLOSED')";
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
        if (Fulfillment.DELIVERING.is(order.get("status"))) {
            order.putAll(db.queryForMap("""
                SELECT d.receiver_name AS receiver,d.phone,
                       d.destination_latitude AS latitude,d.destination_longitude AS longitude
                FROM user_order_detail d
                WHERE d.order_id=?
                """, id));
        }
        order.put("products", db.queryForList("""
            SELECT name_snapshot AS name,quantity FROM user_order_item WHERE order_id=? ORDER BY id
            """, id));
        order.put("refunds", db.queryForList("""
            SELECT status,amount_cents AS amountCents,created_at AS createdAt,channel_status AS channelStatus,channel_detail AS channelDetail,
                   channel_reason AS reason,offline_actual_at AS actualAt
            FROM refund_record WHERE order_id=? ORDER BY created_at,id
            """, id));
        order.put("timeline", db.queryForList("""
            SELECT note,previous_status AS previousStatus,next_status AS nextStatus,UNIX_TIMESTAMP(created_at)*1000 AS createdAt
            FROM order_note WHERE order_id=? AND actor_code IN ('MERCHANT','RIDER_SCAN','RIDER_DELIVERY') ORDER BY id
            """, id));
        return order;
    }

    public Map<String, Object> batches() {
        var config = db.queryForMap("""
            SELECT id,service_start AS serviceStart,service_end AS serviceEnd,peak_start AS peakStart,
                   peak_end AS peakEnd,regular_batch AS regularBatch,peak_batch AS peakBatch
            FROM operations_config ORDER BY id DESC LIMIT 1
            """);
        config.put("peakPeriods", db.queryForList("""
            SELECT start_time AS start,end_time AS end FROM operations_peak_period
            WHERE config_id=? ORDER BY start_time
            """, config.remove("id")));
        // 未领取列表只显示领餐所需信息，隐藏寝室、用户身份和二维码凭据。
        var waiting = db.queryForList("""
            SELECT o.merchant,o.id AS code,d.arrived_at AS arrivedAt,
                   d.batch_alert_at AS alertAt
            FROM business_order o JOIN user_order_detail d ON d.order_id=o.id
            WHERE o.fulfill_status=? AND d.pickup_rider_id IS NULL AND o.pending_refund=FALSE AND o.locking=FALSE
              AND o.refund_status NOT IN ('REFUND_FAILED','PLATFORM_REVIEW')
            ORDER BY d.arrived_at,o.id LIMIT 100
            """, WAITING);
        Long count = db.queryForObject("""
            SELECT COUNT(*) FROM business_order o JOIN user_order_detail d ON d.order_id=o.id
            WHERE o.fulfill_status=? AND d.pickup_rider_id IS NULL AND o.pending_refund=FALSE AND o.locking=FALSE
              AND o.refund_status NOT IN ('REFUND_FAILED','PLATFORM_REVIEW')
            """, Long.class, WAITING);
        return Map.of("waiting", waiting, "count", count, "config", config, "serverTime", System.currentTimeMillis());
    }
}

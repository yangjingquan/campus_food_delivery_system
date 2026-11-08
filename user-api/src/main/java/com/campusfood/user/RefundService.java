package com.campusfood.user;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RefundService {
    private static final long DAY_MILLIS = 24L * 60 * 60 * 1000;
    private static final long REFUND_INTERVAL_MILLIS = 60_000L;
    private final JdbcTemplate db;
    @Value("${app.payment.mode:mock}") private String paymentMode;

    public RefundService(JdbcTemplate db) { this.db = db; }

    @Transactional
    public Map<String, Object> apply(long userId, String orderId, Map<String, Object> body) {
        Map<String, Object> order = lockedOrder(userId, orderId);
        if (!"支付成功".equals(order.get("mergedPaymentStatus"))) throw new ResponseStatusException(HttpStatus.CONFLICT, "该订单当前不能申请售后");
        long now = System.currentTimeMillis();
        ensureWithinWindow(order, now);
        if (hasInFlight(orderId)) throw new ResponseStatusException(HttpStatus.CONFLICT, "该订单已有处理中售后，请先查看处理进度");

        String type = text(body, "type");
        if (!List.of("full", "partial").contains(type)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择全额或部分退款");
        boolean accepted = Boolean.TRUE.equals(order.get("accepted"));
        boolean beforeAcceptance = !accepted && "待商户接单".equals(order.get("fulfill_status"));
        if (beforeAcceptance && !"full".equals(type)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "商户接单前仅支持全额退款");
        if (beforeAcceptance && !"mock".equalsIgnoreCase(paymentMode)) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "微信退款渠道尚未接入，未创建退款申请");
        }

        long paid = number(order, "paid_cents");
        long refunded = number(order, "refunded_cents");
        long food = number(order, "food_cents");
        long foodRefunded = number(order, "food_refunded_cents");
        long amount = "full".equals(type) ? paid - refunded : parseCents(body.get("amountCents"));
        if (amount <= 0 || amount > paid - refunded) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "退款金额超过该订单剩余可退金额");
        if ("partial".equals(type) && amount > food - foodRefunded) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "部分退款仅退餐品，不能超过剩余可退餐品金额");

        String refundId = "AS-" + UUID.randomUUID();
        String reason = text(body, "reason");
        String description = text(body, "description");
        if (reason.isBlank() || reason.length() > 120 || description.length() > 500) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请填写退款原因，补充说明不超过 500 字");
        String status = beforeAcceptance ? "退款处理中" : "待商户处理";
        db.update("INSERT INTO refund_record(id,order_id,payment_id,reason,type,amount_cents,status,channel_status,created_at,operator) VALUES(?,?,?,?,?,?,?,?,?,?)",
            refundId, orderId, order.get("payment_id"), reason, type, amount, status, beforeAcceptance ? "本地模拟退款处理中" : "未发起", now, "用户申请");
        db.update("INSERT INTO user_refund_detail(refund_id,description) VALUES(?,?)", refundId, description);
        addEvent(refundId, "提交售后申请", beforeAcceptance ? "商户尚未接单，按规则自动处理全额退款" : "售后申请已提交，等待商户处理", now);
        db.update("UPDATE business_order SET refund_status=?,pending_refund=TRUE WHERE id=?", status, orderId);
        if (beforeAcceptance) issueLocalRefund(refundId, orderId, now);
        return detail(userId, refundId);
    }

    @Transactional
    public Map<String, Object> merchantDecision(String refundId, String decision, String reason) {
        Map<String, Object> refund = lockedRefund(refundId);
        if (!"待商户处理".equals(refund.get("status"))) throw new ResponseStatusException(HttpStatus.CONFLICT, "该售后已处理或不在待处理状态");
        if ("reject".equals(decision)) {
            if (reason == null || reason.isBlank() || reason.length() > 500) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请填写拒绝原因");
            db.update("UPDATE refund_record SET status='拒绝退款',channel_status='商户拒绝',channel_reason=?,operator='商户处理' WHERE id=?", reason, refundId);
            db.update("UPDATE business_order SET refund_status='拒绝退款',pending_refund=FALSE WHERE id=?", refund.get("order_id"));
            addEvent(refundId, "商户拒绝退款", reason, System.currentTimeMillis());
        } else if ("approve".equals(decision)) {
            if (!"mock".equalsIgnoreCase(paymentMode)) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "微信退款渠道尚未接入，申请仍保留为待处理");
            long now = System.currentTimeMillis();
            db.update("UPDATE refund_record SET status='退款处理中',channel_status='本地模拟退款处理中',operator='商户处理' WHERE id=?", refundId);
            db.update("UPDATE business_order SET refund_status='退款处理中' WHERE id=?", refund.get("order_id"));
            addEvent(refundId, "商户同意退款", "开始处理退款", now);
            issueLocalRefund(refundId, refund.get("order_id").toString(), now);
        } else {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "处理决定无效");
        }
        return publicDetail(refundId);
    }

    @Transactional
    public Map<String, Object> intervene(long userId, String refundId) {
        Map<String, Object> refund = ownedRefund(userId, refundId, true);
        String status = refund.get("status").toString();
        if (!List.of("拒绝退款", "待商户处理", "退款结果核实中").contains(status)) throw new ResponseStatusException(HttpStatus.CONFLICT, "当前售后状态不支持申请平台介入");
        db.update("UPDATE refund_record SET status='平台介入',operator='用户申请平台介入' WHERE id=?", refundId);
        db.update("UPDATE business_order SET refund_status='平台介入',intervene=TRUE,intervene_reason='用户申请平台介入' WHERE id=?", refund.get("order_id"));
        addEvent(refundId, "用户申请平台介入", "平台将协助核实处理", System.currentTimeMillis());
        return detail(userId, refundId);
    }

    @Transactional
    public Map<String, Object> endAfterSale(long userId, String refundId) {
        Map<String, Object> refund = ownedRefund(userId, refundId, true);
        if (!"拒绝退款".equals(refund.get("status"))) throw new ResponseStatusException(HttpStatus.CONFLICT, "当前售后状态不能结束");
        db.update("UPDATE refund_record SET status='已结束',operator='用户结束售后' WHERE id=?", refundId);
        db.update("UPDATE business_order SET refund_status='已结束',pending_refund=FALSE WHERE id=?", refund.get("order_id"));
        addEvent(refundId, "售后结束", "订单继续按当前履约状态处理", System.currentTimeMillis());
        return detail(userId, refundId);
    }

    @Transactional
    public Map<String, Object> internalResult(String refundId, String outcome, String reason) {
        Map<String, Object> refund = lockedRefund(refundId);
        String current = refund.get("status").toString();
        if (!List.of("退款处理中", "退款结果核实中").contains(current)) throw new ResponseStatusException(HttpStatus.CONFLICT, "退款不处于可更新状态");
        long now = System.currentTimeMillis();
        if ("success".equals(outcome)) {
            completeRefund(refundId, refund.get("order_id").toString(), now, "微信渠道确认退款成功");
        } else if ("failure".equals(outcome)) {
            String why = reason == null || reason.isBlank() ? "渠道明确返回失败" : reason.substring(0, Math.min(500, reason.length()));
            db.update("UPDATE refund_record SET status='退款失败',channel_status='退款失败',channel_reason=?,operator='退款渠道' WHERE id=?", why, refundId);
            db.update("UPDATE business_order SET refund_status='退款失败',pending_refund=FALSE,intervene=TRUE,intervene_reason='退款失败，需线下核实' WHERE id=?", refund.get("order_id"));
            db.update("UPDATE user_refund_detail SET original_online_failure=? WHERE refund_id=?", why, refundId);
            addEvent(refundId, "线上退款失败", why, now);
        } else if ("unknown".equals(outcome)) {
            String why = reason == null || reason.isBlank() ? "退款结果待核实" : reason.substring(0, Math.min(500, reason.length()));
            db.update("UPDATE refund_record SET status='退款结果核实中',channel_status='结果未知',channel_reason=?,operator='退款渠道' WHERE id=?", why, refundId);
            db.update("UPDATE business_order SET refund_status='退款结果核实中',pending_refund=TRUE WHERE id=?", refund.get("order_id"));
            addEvent(refundId, "退款结果待核实", "先核实原退款单状态，不重复发起退款", now);
        } else {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "退款渠道结果无效");
        }
        return publicDetail(refundId);
    }

    @Transactional
    public Map<String, Object> confirmOffline(String refundId, Map<String, Object> body) {
        Map<String, Object> refund = lockedRefund(refundId);
        if (!"退款失败".equals(refund.get("status"))) throw new ResponseStatusException(HttpStatus.CONFLICT, "仅明确失败的退款可以登记线下处理");
        long actualAmount = parseCents(body.get("actualAmountCents"));
        long expectedAmount = number(refund, "amount_cents");
        if (actualAmount != expectedAmount) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "线下退款金额须与该申请金额一致");
        String channel = text(body, "channel"), proof = text(body, "proof");
        if (channel.isBlank() || channel.length() > 80 || proof.isBlank() || proof.length() > 500) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请填写线下渠道和处理凭据");
        String oldFailure = String.valueOf(refund.get("channel_reason"));
        db.update("UPDATE user_refund_detail SET original_online_failure=? WHERE refund_id=?", oldFailure, refundId);
        long actualAt = System.currentTimeMillis();
        completeRefund(refundId, refund.get("order_id").toString(), actualAt, "已核实线下实际退款成功：" + channel);
        db.update("UPDATE refund_record SET status='线下已处理',channel_status='线上失败；线下已核实',channel_reason=?,operator='线下核实' WHERE id=?", limit(proof, 500), refundId);
        db.update("UPDATE business_order SET refund_status='线下已处理' WHERE id=?", refund.get("order_id"));
        db.update("UPDATE user_refund_detail SET original_online_failure=? WHERE refund_id=?", oldFailure, refundId);
        addEvent(refundId, "线下已处理", "已记录实际退款渠道、凭据与时间", actualAt);
        return publicDetail(refundId);
    }

    public Map<String, Object> detail(long userId, String refundId) {
        ownedRefund(userId, refundId, false);
        Map<String, Object> result = publicDetail(refundId);
        result.put("events", db.queryForList("SELECT status,message,created_at AS createdAt FROM user_after_sale_event WHERE refund_id=? ORDER BY id", refundId));
        return result;
    }

    @Scheduled(fixedDelayString = "${app.after-sale-scan-ms:15000}", initialDelayString = "${app.after-sale-initial-delay-ms:5000}")
    @Transactional
    public void scheduledWork() {
        processWaitingRefunds();
        escalateUnansweredRefunds();
        autoRefundUnacceptedOrders();
    }

    @Transactional
    public void processWaitingRefunds() {
        long now = System.currentTimeMillis();
        List<String> ids = db.queryForList("SELECT r.id FROM refund_record r JOIN user_refund_detail x ON x.refund_id=r.id WHERE r.status='等待退款提交' AND x.wait_until<=? ORDER BY x.wait_until LIMIT 50", String.class, now);
        for (String id : ids) {
            Map<String, Object> refund = lockedRefund(id);
            if ("等待退款提交".equals(refund.get("status"))) issueLocalRefund(id, refund.get("order_id").toString(), now);
        }
    }

    @Transactional
    public void escalateUnansweredRefunds() {
        long deadline = System.currentTimeMillis() - 3L * 60 * 60 * 1000;
        List<String> ids = db.queryForList("SELECT id FROM refund_record WHERE status='待商户处理' AND created_at<=? ORDER BY created_at LIMIT 50", String.class, deadline);
        for (String id : ids) {
            Map<String, Object> refund = lockedRefund(id);
            if (!"待商户处理".equals(refund.get("status"))) continue;
            db.update("UPDATE refund_record SET status='平台介入',operator='系统超时介入' WHERE id=?", id);
            db.update("UPDATE business_order SET refund_status='平台介入',intervene=TRUE,intervene_reason='商户超过 3 小时未处理售后' WHERE id=?", refund.get("order_id"));
            addEvent(id, "商户超时，平台介入", "商户超过 3 小时未处理售后", System.currentTimeMillis());
        }
    }

    @Transactional
    public void autoRefundUnacceptedOrders() {
        List<String> orderIds = db.queryForList("SELECT o.id FROM business_order o JOIN payment_order p ON p.id=o.payment_id JOIN operations_config c ON c.id=(SELECT MAX(id) FROM operations_config) WHERE o.payment_status IN ('支付成功','本地模拟支付成功') AND p.status='支付成功' AND o.fulfill_status='待商户接单' AND o.accepted=FALSE AND o.paid_at>0 AND o.paid_at+c.accept_timeout*60000<=? AND NOT EXISTS (SELECT 1 FROM refund_record r WHERE r.order_id=o.id AND r.status IN ('退款处理中','退款结果核实中','退款成功','退款失败','平台介入','待商户处理','等待退款提交')) ORDER BY o.paid_at LIMIT 50", String.class, System.currentTimeMillis());
        for (String orderId : orderIds) createTimeoutRefund(orderId);
    }

    @Transactional
    public Map<String, Object> merchantDecisionInternal(String refundId, Map<String, Object> body) {
        return merchantDecision(refundId, text(body, "decision"), text(body, "reason"));
    }

    private void createTimeoutRefund(String orderId) {
        List<Map<String, Object>> rows = db.queryForList("SELECT o.payment_id,o.paid_cents,o.refunded_cents,o.id FROM business_order o WHERE o.id=? FOR UPDATE", orderId);
        if (rows.isEmpty()) return;
        Map<String, Object> order = rows.get(0);
        long amount = number(order, "paid_cents") - number(order, "refunded_cents");
        if (amount <= 0) return;
        String refundId = "AS-AUTO-" + UUID.randomUUID();
        long now = System.currentTimeMillis();
        db.update("INSERT INTO refund_record(id,order_id,payment_id,reason,type,amount_cents,status,channel_status,created_at,operator) VALUES(?,?,?,?,?,?,?,?,?,?)",
            refundId, orderId, order.get("payment_id"), "商户接单超时", "full", amount, "退款处理中", "本地模拟自动退款", now, "系统超时处理");
        db.update("INSERT INTO user_refund_detail(refund_id,description) VALUES(?,?)", refundId, "商户超过运营配置的接单时限未接单");
        db.update("UPDATE business_order SET refund_status='退款处理中',pending_refund=TRUE WHERE id=?", orderId);
        addEvent(refundId, "商户接单超时，自动发起退款", "接单超时时长按运营配置执行", now);
        if ("mock".equalsIgnoreCase(paymentMode)) issueLocalRefund(refundId, orderId, now);
        else {
            db.update("UPDATE refund_record SET status='退款失败',channel_status='渠道未配置',channel_reason='微信退款渠道未配置，需管理员线下核实' WHERE id=?", refundId);
            db.update("UPDATE business_order SET refund_status='退款失败',pending_refund=FALSE,intervene=TRUE,intervene_reason='退款渠道未配置，需线下核实' WHERE id=?", orderId);
            addEvent(refundId, "退款渠道未配置", "已标记人工处理；未对微信渠道发出退款请求", System.currentTimeMillis());
        }
    }

    private void issueLocalRefund(String refundId, String orderId, long now) {
        Map<String, Object> order = db.queryForMap("SELECT payment_id FROM business_order WHERE id=? FOR UPDATE", orderId);
        Map<String, Object> payment = db.queryForMap("SELECT refund_count,last_refund_at FROM payment_order WHERE id=? FOR UPDATE", order.get("payment_id"));
        int count = ((Number) payment.get("refund_count")).intValue();
        Object lastRaw = payment.get("last_refund_at");
        Long last = lastRaw instanceof Number n ? n.longValue() : null;
        if (count >= 50) {
            db.update("UPDATE refund_record SET status='平台介入',channel_status='合并支付单退款次数达到上限' WHERE id=?", refundId);
            db.update("UPDATE business_order SET refund_status='平台介入',pending_refund=FALSE,intervene=TRUE,intervene_reason='合并支付单退款次数达到微信侧限制' WHERE id=?", orderId);
            addEvent(refundId, "平台介入", "该合并支付单已达到退款次数限制", now);
            return;
        }
        if (last != null && now - last < REFUND_INTERVAL_MILLIS) {
            long waitUntil = last + REFUND_INTERVAL_MILLIS;
            db.update("UPDATE refund_record SET status='等待退款提交',channel_status='同一支付单退款间隔限制' WHERE id=?", refundId);
            db.update("UPDATE user_refund_detail SET wait_until=? WHERE refund_id=?", waitUntil, refundId);
            db.update("UPDATE business_order SET refund_status='退款处理中',pending_refund=TRUE WHERE id=?", orderId);
            addEvent(refundId, "等待退款提交", "同一合并支付单退款需间隔处理", now);
            return;
        }
        db.update("UPDATE payment_order SET refund_count=refund_count+1,last_refund_at=? WHERE id=?", now, order.get("payment_id"));
        db.update("UPDATE refund_record SET status='退款处理中',channel_status='本地模拟退款处理中' WHERE id=?", refundId);
        completeRefund(refundId, orderId, now, "本地模拟退款成功；未调用微信支付");
    }

    private void completeRefund(String refundId, String orderId, long now, String message) {
        Map<String, Object> order = db.queryForMap("SELECT paid_cents,food_cents,refunded_cents,food_refunded_cents,accepted,fulfill_status FROM business_order WHERE id=? FOR UPDATE", orderId);
        Map<String, Object> refund = db.queryForMap("SELECT amount_cents,type FROM refund_record WHERE id=? FOR UPDATE", refundId);
        long amount = number(refund, "amount_cents");
        long remainingPaid = number(order, "paid_cents") - number(order, "refunded_cents");
        long remainingFood = number(order, "food_cents") - number(order, "food_refunded_cents");
        if (amount <= 0 || amount > remainingPaid || ("partial".equals(refund.get("type")) && amount > remainingFood)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "退款金额状态已变化，不能完成本次退款");
        }
        long foodRefund = "partial".equals(refund.get("type")) ? amount : Math.min(amount, remainingFood);
        db.update("UPDATE business_order SET refunded_cents=refunded_cents+?,food_refunded_cents=food_refunded_cents+?,refund_status='退款成功',pending_refund=FALSE,fulfill_status='已关闭',settlement_status='售后暂缓',closed_at=COALESCE(closed_at,?),payment_last_refund_at=?,last_refund_at=? WHERE id=?",
            amount, foodRefund, now, now, now, orderId);
        db.update("UPDATE user_order_detail SET first_closed_at=COALESCE(first_closed_at,?) WHERE order_id=?", now, orderId);
        db.update("UPDATE refund_record SET status='退款成功',channel_status=?,channel_reason=NULL WHERE id=?", message, refundId);
        if (!Boolean.TRUE.equals(order.get("accepted")) && "待商户接单".equals(order.get("fulfill_status"))) restoreStock(orderId);
        db.update("DELETE FROM delivery_location WHERE order_id=?", orderId);
        addEvent(refundId, "退款成功，订单已关闭", message, now);
    }

    private void restoreStock(String orderId) {
        List<Map<String, Object>> items = db.queryForList("SELECT product_id,quantity FROM user_order_item WHERE order_id=?", orderId);
        for (Map<String, Object> item : items) {
            db.update("UPDATE merchant_product SET stock=stock+?,reserved=GREATEST(0,reserved-?) WHERE id=?",
                item.get("quantity"), item.get("quantity"), item.get("product_id"));
        }
    }

    private boolean hasInFlight(String orderId) {
        Integer count = db.queryForObject("SELECT COUNT(*) FROM refund_record WHERE order_id=? AND status IN ('待商户处理','协商中','退款处理中','退款结果核实中','等待退款提交','平台介入')", Integer.class, orderId);
        return count != null && count > 0;
    }

    private void ensureWithinWindow(Map<String, Object> order, long now) {
        Long completedAt = nullableNumber(order.get("completed_at"));
        Long firstClosedAt = nullableNumber(order.get("first_closed_at"));
        Long deadline = null;
        if (completedAt != null) deadline = completedAt + DAY_MILLIS;
        else if (firstClosedAt != null) deadline = firstClosedAt + DAY_MILLIS;
        if (deadline != null && now > deadline) throw new ResponseStatusException(HttpStatus.GONE, "已超过售后申请时限");
    }

    private Map<String, Object> lockedOrder(long userId, String orderId) {
        List<Map<String, Object>> rows = db.queryForList("SELECT o.id,o.payment_id,o.paid_cents,o.food_cents,o.refunded_cents,o.food_refunded_cents,o.payment_status,o.fulfill_status,o.accepted,o.completed_at,d.first_closed_at,p.status AS mergedPaymentStatus FROM business_order o JOIN user_order_detail d ON d.order_id=o.id JOIN payment_order p ON p.id=o.payment_id WHERE o.id=? AND d.user_id=? FOR UPDATE", orderId, userId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "订单不存在");
        return rows.get(0);
    }

    private Map<String, Object> ownedRefund(long userId, String refundId, boolean lock) {
        String sql = "SELECT r.id,r.order_id,r.payment_id,r.reason,r.type,r.amount_cents,r.status,r.channel_status,r.channel_reason,r.created_at,r.operator,d.description,d.original_online_failure,d.wait_until,o.refunded_cents,o.food_refunded_cents FROM refund_record r JOIN user_order_detail u ON u.order_id=r.order_id JOIN business_order o ON o.id=r.order_id LEFT JOIN user_refund_detail d ON d.refund_id=r.id WHERE r.id=? AND u.user_id=?" + (lock ? " FOR UPDATE" : "");
        List<Map<String, Object>> rows = db.queryForList(sql, refundId, userId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "售后记录不存在");
        return rows.get(0);
    }

    private Map<String, Object> lockedRefund(String refundId) {
        List<Map<String, Object>> rows = db.queryForList("SELECT id,order_id,payment_id,reason,type,amount_cents,status,channel_status,channel_reason,created_at FROM refund_record WHERE id=? FOR UPDATE", refundId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "售后记录不存在");
        return rows.get(0);
    }

    private Map<String, Object> publicDetail(String refundId) {
        List<Map<String, Object>> rows = db.queryForList("SELECT r.id,r.order_id AS orderId,r.payment_id AS paymentId,r.reason,r.type,r.amount_cents AS amountCents,r.status,r.channel_status AS channelStatus,r.channel_reason AS channelReason,r.created_at AS createdAt,r.operator,d.description,d.wait_until AS waitUntil,d.original_online_failure AS originalOnlineFailure,o.fulfill_status AS fulfillmentStatus,o.refunded_cents AS refundedCents,o.food_refunded_cents AS foodRefundedCents FROM refund_record r JOIN business_order o ON o.id=r.order_id LEFT JOIN user_refund_detail d ON d.refund_id=r.id WHERE r.id=?", refundId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "售后记录不存在");
        Map<String, Object> result = new java.util.LinkedHashMap<>(rows.get(0));
        result.put("events", db.queryForList("SELECT status,message,created_at AS createdAt FROM user_after_sale_event WHERE refund_id=? ORDER BY id", refundId));
        return result;
    }

    private void addEvent(String refundId, String status, String message, long now) {
        db.update("INSERT INTO user_after_sale_event(refund_id,status,message,created_at) VALUES(?,?,?,?)", refundId, status, message, Timestamp.from(java.time.Instant.ofEpochMilli(now)));
    }

    private static long parseCents(Object value) {
        try { return Long.parseLong(String.valueOf(value)); }
        catch (Exception exception) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "退款金额格式无效"); }
    }
    private static long number(Map<String, Object> map, String key) { Object value = map.get(key); return value == null ? 0L : ((Number) value).longValue(); }
    private static Long nullableNumber(Object value) { return value == null ? null : ((Number) value).longValue(); }
    private static String text(Map<String, Object> body, String key) { return body.get(key) == null ? "" : body.get(key).toString().trim(); }
    private static String limit(String value, int max) { return value.length() <= max ? value : value.substring(0, max); }
}

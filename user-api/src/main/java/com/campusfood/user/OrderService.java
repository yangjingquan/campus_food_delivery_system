package com.campusfood.user;

import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.sql.Timestamp;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class OrderService {
    private record RequestedLine(String productId, int quantity) {}
    private record CatalogLine(String productId, String merchantId, String merchantName, String productName,
                               String description, long priceCents, int stock, int reserved, String businessStatus) {}
    private record OrderLine(CatalogLine product, int quantity) {}

    private final JdbcTemplate db;
    private final AddressService addresses;
    @Autowired private WechatConfigurationService wechatConfiguration;
    @Autowired private WechatPayClient wechatPay;
    @Autowired private PlatformTransactionManager transactionManager;
    @Value("${app.payment.mode:disabled}") private String paymentMode;
    @Value("${app.unpaid-timeout-minutes:30}") private int unpaidTimeoutMinutes;

    public OrderService(JdbcTemplate db, AddressService addresses) { this.db = db; this.addresses = addresses; }

    @Transactional
    public Map<String, Object> checkout(long userId, Map<String, Object> body) {
        boolean realPayment = "wechat".equals(paymentMode);
        Long configurationVersion = realPayment ? wechatConfiguration.currentPaymentVersion() : null;
        if (!"mock".equals(paymentMode) && configurationVersion == null) {
            throw WechatConfigurationService.paymentUnavailable();
        }
        if (configurationVersion != null) wechatConfiguration.payment(configurationVersion);
        String addressId = text(body, "addressId");
        if (addressId.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择有效的收餐地址");
        Map<String, Object> address = addresses.require(userId, addressId);
        List<RequestedLine> requested = parseItems(body.get("items"));
        String requestKey = text(body, "requestKey");
        if (!requestKey.matches("[A-Za-z0-9_-]{8,80}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "下单请求标识无效");
        String fingerprint = fingerprint(addressId, requested);
        // 并发重试先锁同一用户，再判断请求编号是否已创建支付单。
        db.queryForObject("SELECT id FROM user_profile WHERE id=? FOR UPDATE", Long.class, userId);
        List<Map<String, Object>> previous = db.queryForList("SELECT id,request_fingerprint FROM payment_order WHERE user_id=? AND request_key=? FOR UPDATE", userId, requestKey);
        if (!previous.isEmpty()) {
            if (!fingerprint.equals(previous.get(0).get("request_fingerprint"))) throw new ResponseStatusException(HttpStatus.CONFLICT, "该下单请求标识已用于其他购物车，请刷新后重试");
            return checkoutResponse(previous.get(0).get("id").toString());
        }
        List<OrderLine> lines = lockAndValidate(requested);
        Map<String, Object> operations = db.queryForMap(
            "SELECT fee_cents,id,food_rate FROM operations_config ORDER BY id DESC LIMIT 1");
        long feePerMerchant = ((Number) operations.get("fee_cents")).longValue();
        if (feePerMerchant < 0) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "配送费用配置无效");

        Map<String, List<OrderLine>> grouped = new LinkedHashMap<>();
        for (OrderLine line : lines) grouped.computeIfAbsent(line.product().merchantId(), ignored -> new ArrayList<>()).add(line);
        long foodTotal = 0;
        for (OrderLine line : lines) foodTotal = Math.addExact(foodTotal, Math.multiplyExact(line.product().priceCents(), line.quantity()));
        long deliveryTotal = Math.multiplyExact(feePerMerchant, grouped.size());
        long total = Math.addExact(foodTotal, deliveryTotal);
        Object quoted = body.get("expectedTotalCents");
        if (quoted != null && (!(quoted instanceof Number cents) || cents.longValue() != total
                || cents.doubleValue() != total)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "商品价格或配送费有变化，请重新确认");
        }
        if (total <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "订单金额无效");

        // 微信商户订单号最多 32 个字符，同一支付单在重试时始终使用同一个号码。
        String paymentId = realPayment ? UUID.randomUUID().toString().replace("-", "") : "P-" + UUID.randomUUID();
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(Math.max(1, unpaidTimeoutMinutes) * 60L);
        db.update("INSERT INTO payment_order(id,user_id,request_key,request_fingerprint,amount_cents,status,payment_mode,expires_at) VALUES(?,?,?,?,?,?,?,?)",
            paymentId, userId, requestKey, fingerprint, total, "未支付", paymentMode, Timestamp.from(expiresAt));

        if (realPayment) {
            db.update("UPDATE payment_order SET wechat_config_version=? WHERE id=?", configurationVersion, paymentId);
        }

        List<Map<String, Object>> merchantOrders = new ArrayList<>();
        for (Map.Entry<String, List<OrderLine>> entry : grouped.entrySet()) {
            List<OrderLine> merchantLines = entry.getValue();
            CatalogLine first = merchantLines.get(0).product();
            long foodCents = 0;
            StringBuilder summary = new StringBuilder();
            for (OrderLine line : merchantLines) {
                CatalogLine item = line.product();
                long amount = Math.multiplyExact(item.priceCents(), line.quantity());
                foodCents = Math.addExact(foodCents, amount);
                if (!summary.isEmpty()) summary.append("、");
                summary.append(item.productName()).append(" × ").append(line.quantity());
                int changed = db.update("UPDATE merchant_product SET reserved=reserved+? WHERE id=? AND available=TRUE AND stock-reserved>=?",
                    line.quantity(), item.productId(), line.quantity());
                if (changed != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, item.productName() + " 库存刚刚变化，请重新确认");
            }
            String orderId = "O-" + UUID.randomUUID();
            long merchantTotal = Math.addExact(foodCents, feePerMerchant);
            db.update("INSERT INTO business_order(id,payment_id,merchant_id,merchant,items,food_cents,delivery_cents,paid_cents,payment_status,fulfill_status,refund_status,settlement_status,accepted,pending_refund,locking,payment_count,paid_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                orderId, paymentId, entry.getKey(), first.merchantName(), limit(summary.toString(), 490), foodCents, feePerMerchant, merchantTotal,
                "未支付", "待支付", "无售后", "待支付", false, false, false, 0, 0L);
            db.update("""
                INSERT INTO user_order_detail(order_id,user_id,payment_id,address_id,receiver_name,phone,building,room,
                    operations_rule_id,food_rate_snapshot)
                VALUES(?,?,?,?,?,?,?,?,?,?)
                """, orderId, userId, paymentId, addressId, address.get("name"), address.get("phone"),
                address.get("building"), address.get("room"), operations.get("id"), operations.get("food_rate"));
            for (OrderLine line : merchantLines) {
                CatalogLine item = line.product();
                long amount = Math.multiplyExact(item.priceCents(), line.quantity());
                db.update("INSERT INTO user_order_item(order_id,product_id,name_snapshot,price_cents,quantity,amount_cents) VALUES(?,?,?,?,?,?)",
                    orderId, item.productId(), item.productName(), item.priceCents(), line.quantity(), amount);
            }
            merchantOrders.add(Map.of("id", orderId, "merchantId", entry.getKey(), "merchantName", first.merchantName(),
                "foodCents", foodCents, "deliveryFeeCents", feePerMerchant, "amountCents", merchantTotal,
                "status", "待支付", "itemCount", merchantLines.stream().mapToInt(OrderLine::quantity).sum()));
        }
        return Map.of("payment", Map.of("id", paymentId, "amountCents", total, "status", "未支付", "expiresAt", expiresAt.toEpochMilli(), "mode", paymentMode),
            "merchantOrders", merchantOrders, "foodCents", foodTotal, "deliveryFeeCents", deliveryTotal, "totalCents", total,
            "merchantCount", merchantOrders.size());
    }

    private Map<String, Object> checkoutResponse(String paymentId) {
        Map<String, Object> payment = db.queryForMap("SELECT id,amount_cents,status,payment_mode,expires_at FROM payment_order WHERE id=?", paymentId);
        List<Map<String, Object>> orders = db.queryForList("SELECT id,merchant_id AS merchantId,merchant AS merchantName,food_cents AS foodCents,delivery_cents AS deliveryFeeCents,paid_cents AS amountCents,fulfill_status AS status FROM business_order WHERE payment_id=? ORDER BY merchant_id", paymentId);
        long foodCents = orders.stream().mapToLong(row -> ((Number) row.get("foodCents")).longValue()).sum();
        long deliveryFeeCents = orders.stream().mapToLong(row -> ((Number) row.get("deliveryFeeCents")).longValue()).sum();
        return Map.of("payment", Map.of("id", payment.get("id"), "amountCents", payment.get("amount_cents"), "status", payment.get("status"),
                "expiresAt", ((Timestamp) payment.get("expires_at")).getTime(), "mode", payment.get("payment_mode")),
            "merchantOrders", orders, "foodCents", foodCents, "deliveryFeeCents", deliveryFeeCents,
            "totalCents", ((Number) payment.get("amount_cents")).longValue(), "merchantCount", orders.size());
    }

    @Transactional
    public Map<String, Object> mockPayment(long userId, String paymentId) {
        if (!"mock".equalsIgnoreCase(paymentMode)) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "当前支付渠道未配置，不能模拟或确认支付");
        Map<String, Object> payment = paymentRow(userId, paymentId, true);
        if (!"mock".equals(payment.get("payment_mode"))) throw WechatConfigurationService.paymentUnavailable();
        if ("支付成功".equals(payment.get("status"))) return payment(userId, paymentId);
        if (!"未支付".equals(payment.get("status"))) throw new ResponseStatusException(HttpStatus.CONFLICT, "支付单已处理");
        long now = System.currentTimeMillis();
        Timestamp expires = (Timestamp) payment.get("expires_at");
        if (expires.toInstant().toEpochMilli() <= now) {
            expireUnpaid(paymentId, now);
            return Map.of("id", paymentId, "status", "已关闭", "amountCents", payment.get("amount_cents"), "merchantOrders", List.of());
        }
        markPaid(payment, "LOCAL-" + paymentId, now, true);
        return payment(userId, paymentId);
    }

    @Transactional
    public void beginWechatPayment(long userId, String id) {
        if (!"wechat".equals(paymentMode)) throw WechatConfigurationService.paymentUnavailable();
        Map<String, Object> row = paymentRow(userId, id, true);
        checkCanPrepare(row);
        // 下单请求可能在网络超时后仍被微信受理，必须先持久化尝试标记。
        db.update("UPDATE payment_order SET wechat_started=TRUE WHERE id=?", id);
    }

    @Transactional
    public Map<String, String> prepareWechatPayment(long userId, String id) {
        if (!"wechat".equals(paymentMode)) throw WechatConfigurationService.paymentUnavailable();
        Map<String, Object> row = paymentRow(userId, id, true);
        checkCanPrepare(row);
        Instant expiry = ((Timestamp) row.get("expires_at")).toInstant();
        var config = paymentConfig(row);
        String prepayId = (String) row.get("prepay_id");
        if (prepayId == null) {
            String openid = db.queryForObject("SELECT openid FROM user_profile WHERE id=?", String.class, userId);
            prepayId = wechatPay.prepay(config, id, openid, ((Number) row.get("amount_cents")).longValue(), expiry);
            db.update("UPDATE payment_order SET prepay_id=? WHERE id=?", prepayId, id);
        }
        return wechatPay.parameters(config, prepayId);
    }

    private void checkCanPrepare(Map<String, Object> row) {
        if (!"wechat".equals(row.get("payment_mode")) || !"未支付".equals(row.get("status"))
                || !Instant.now().isBefore(((Timestamp) row.get("expires_at")).toInstant())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "支付单已处理或超时，请刷新支付状态");
        }
    }

    @Transactional
    public Map<String, Object> syncPayment(long userId, String id) {
        Map<String, Object> row = paymentRow(userId, id, true);
        if (Boolean.TRUE.equals(row.get("wechat_started")) && "未支付".equals(row.get("status"))) {
            var config = paymentConfig(row);
            reconcile(row, config, wechatPay.query(config, id));
        }
        return payment(userId, id);
    }

    @Transactional
    public void wechatNotification(String timestamp, String nonce, String serial, String signature, String body) {
        Long version = wechatConfiguration.currentPaymentVersion();
        if (version == null) throw WechatConfigurationService.paymentUnavailable();
        JsonNode result = wechatPay.notification(wechatConfiguration.payment(version), timestamp, nonce, serial, signature, body);
        String id = result.path("out_trade_no").asText();
        Map<String, Object> row = db.queryForMap("SELECT * FROM payment_order WHERE id=? FOR UPDATE", id);
        if (!reconcile(row, paymentConfig(row), result)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "微信支付通知状态无效");
        }
    }

    private WechatConfigurationService.PaymentCredentials paymentConfig(Map<String, Object> row) {
        if (!"wechat".equals(row.get("payment_mode")) || !(row.get("wechat_config_version") instanceof Number version)) {
            throw WechatConfigurationService.paymentUnavailable();
        }
        return wechatConfiguration.payment(version.longValue());
    }

    private boolean reconcile(Map<String, Object> row, WechatConfigurationService.PaymentCredentials config, JsonNode result) {
        if (!"SUCCESS".equals(result.path("trade_state").asText())) return false;
        var amount = result.path("amount");
        String transactionId = result.path("transaction_id").asText();
        if (!row.get("id").equals(result.path("out_trade_no").asText())
                || !config.appId().equals(result.path("appid").asText())
                || !config.merchant().equals(result.path("mchid").asText())
                || !"CNY".equals(amount.path("currency").asText())
                || amount.path("total").asLong(-1) != ((Number) row.get("amount_cents")).longValue()
                || !transactionId.matches("[0-9]{1,64}")) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "微信支付凭据与订单不匹配");
        }
        long paidAt = java.time.OffsetDateTime.parse(result.path("success_time").asText()).toInstant().toEpochMilli();
        markPaid(row, transactionId, paidAt, false);
        return true;
    }

    private void markPaid(Map<String, Object> row, String transactionId, long now, boolean mock) {
        String paymentId = (String) row.get("id");
        if ("支付成功".equals(row.get("status")) && transactionId.equals(row.get("transaction_id"))) return;
        int changed = db.update("""
                UPDATE payment_order SET status='支付成功',paid_at=?,transaction_id=?
                WHERE id=? AND status='未支付'
                """, now, transactionId, paymentId);
        if (changed != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "支付状态已变化，请刷新订单");
        List<String> orderIds = db.queryForList("SELECT id FROM business_order WHERE payment_id=? ORDER BY merchant_id", String.class, paymentId);
        for (String orderId : orderIds) {
            db.update("""
                UPDATE business_order SET payment_status=?,fulfill_status='待商户接单',settlement_status='待结算',paid_at=?
                WHERE id=? AND payment_status='未支付'
                """, mock ? "本地模拟支付成功" : "支付成功", now, orderId);
            for (Map<String, Object> item : db.queryForList("SELECT product_id,quantity FROM user_order_item WHERE order_id=?", orderId)) {
                int quantity = ((Number) item.get("quantity")).intValue();
                int inventoryChanged = db.update("UPDATE merchant_product SET stock=stock-?,reserved=reserved-?,sold_count=sold_count+? WHERE id=? AND stock>=? AND reserved>=?",
                    quantity, quantity, quantity, item.get("product_id"), quantity, quantity);
                if (inventoryChanged != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "库存状态变化，支付订单已锁定，请联系平台核实");
            }
        }
    }

    public Map<String, Object> payment(long userId, String paymentId) {
        Map<String, Object> row = paymentRow(userId, paymentId, false);
        List<Map<String, Object>> orders = db.queryForList("SELECT id,merchant,food_cents AS foodCents,delivery_cents AS deliveryFeeCents,paid_cents AS amountCents,fulfill_status AS status FROM business_order WHERE payment_id=? ORDER BY merchant_id", paymentId);
        long foodCents = orders.stream().mapToLong(item -> ((Number) item.get("foodCents")).longValue()).sum();
        long deliveryFeeCents = orders.stream().mapToLong(item -> ((Number) item.get("deliveryFeeCents")).longValue()).sum();
        return Map.of("id", row.get("id"), "amountCents", row.get("amount_cents"), "status", row.get("status"),
            "expiresAt", ((Timestamp) row.get("expires_at")).getTime(), "mode", row.get("payment_mode"), "merchantOrders", orders,
            "foodCents", foodCents, "deliveryFeeCents", deliveryFeeCents, "merchantCount", orders.size());
    }

    public List<Map<String, Object>> list(long userId, String status) {
        String filter = status == null || status.isBlank() ? "全部" : status;
        return db.queryForList("SELECT o.id,o.payment_id AS paymentId,o.merchant_id AS merchantId,o.merchant AS merchantName,o.items,o.food_cents AS foodCents,o.delivery_cents AS deliveryFeeCents,o.paid_cents AS paidCents,o.refunded_cents AS refundedCents,o.food_refunded_cents AS foodRefundedCents,o.payment_status AS paymentStatus,o.fulfill_status AS fulfillStatus,o.refund_status AS refundStatus,o.settlement_status AS settlementStatus,o.accepted,o.intervene,o.intervene_reason AS interveneReason,d.created_at AS createdAt,p.status AS mergedPaymentStatus FROM business_order o JOIN user_order_detail d ON d.order_id=o.id JOIN payment_order p ON p.id=o.payment_id WHERE d.user_id=? AND (?='全部' OR o.fulfill_status=? OR p.status=?) ORDER BY d.created_at DESC", userId, filter, filter, filter);
    }

    public Map<String, Object> detail(long userId, String orderId) {
        List<Map<String, Object>> rows = db.queryForList("SELECT o.id,o.payment_id AS paymentId,o.merchant_id AS merchantId,o.merchant AS merchantName,o.items,o.food_cents AS foodCents,o.delivery_cents AS deliveryFeeCents,o.paid_cents AS paidCents,o.refunded_cents AS refundedCents,o.food_refunded_cents AS foodRefundedCents,o.payment_status AS paymentStatus,o.fulfill_status AS fulfillStatus,o.refund_status AS refundStatus,o.settlement_status AS settlementStatus,o.accepted,o.pending_refund AS pendingRefund,o.intervene,o.intervene_reason AS interveneReason,o.paid_at AS paidAt,o.completed_at AS completedAt,o.closed_at AS closedAt,d.address_id AS addressId,d.receiver_name AS receiverName,d.phone,d.building,d.room,d.first_closed_at AS firstClosedAt,p.status AS mergedPaymentStatus FROM business_order o JOIN user_order_detail d ON d.order_id=o.id JOIN payment_order p ON p.id=o.payment_id WHERE d.user_id=? AND o.id=?", userId, orderId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "订单不存在");
        Map<String, Object> result = new LinkedHashMap<>(rows.get(0));
        result.put("items", db.queryForList("SELECT product_id AS productId,name_snapshot AS name,price_cents AS priceCents,quantity,amount_cents AS amountCents FROM user_order_item WHERE order_id=? ORDER BY id", orderId));
        result.put("aftersales", db.queryForList("SELECT r.id,r.reason,r.type,r.amount_cents AS amountCents,r.status,r.channel_status AS channelStatus,r.channel_reason AS channelReason,r.created_at AS createdAt,COALESCE(x.description,'') AS description FROM refund_record r LEFT JOIN user_refund_detail x ON x.refund_id=r.id WHERE r.order_id=? ORDER BY r.created_at DESC", orderId));
        result.put("locationAvailable", "配送中".equals(result.get("fulfillStatus")));
        return result;
    }

    @Transactional
    public boolean expireUnpaid(String paymentId, long now) {
        List<Map<String, Object>> rows = db.queryForList("SELECT * FROM payment_order WHERE id=? FOR UPDATE", paymentId);
        if (rows.isEmpty() || !"未支付".equals(rows.get(0).get("status")) || ((Timestamp) rows.get(0).get("expires_at")).getTime() > now) return false;
        Map<String, Object> row = rows.get(0);
        if (Boolean.TRUE.equals(row.get("wechat_started"))) {
            var config = paymentConfig(row);
            JsonNode remote = wechatPay.query(config, paymentId);
            if (reconcile(row, config, remote)) return false;
            String state = remote.path("trade_state").asText();
            // 微信未确认关单时不能释放库存，避免用户已经扣款但订单被关闭。
            if ("NOTPAY".equals(state)) wechatPay.close(config, paymentId);
            else if (!"CLOSED".equals(state) && !"ORDER_NOT_EXIST".equals(remote.path("code").asText())) return false;
        }
        db.update("UPDATE payment_order SET status='已关闭' WHERE id=? AND status='未支付'", paymentId);
        List<String> orderIds = db.queryForList("SELECT id FROM business_order WHERE payment_id=?", String.class, paymentId);
        for (String orderId : orderIds) {
            releaseReservations(orderId);
            db.update("UPDATE business_order SET payment_status='已关闭',fulfill_status='已关闭',settlement_status='已关闭',closed_at=? WHERE id=? AND payment_status='未支付'", now, orderId);
        }
        return true;
    }

    @Scheduled(fixedDelayString = "${app.unpaid-scan-ms:30000}", initialDelayString = "${app.unpaid-initial-delay-ms:5000}")
    public void expireUnpaidOrders() {
        List<String> expired = db.queryForList("SELECT id FROM payment_order WHERE status='未支付' AND expires_at<=CURRENT_TIMESTAMP ORDER BY expires_at LIMIT 100", String.class);
        long now = System.currentTimeMillis();
        var transactions = new TransactionTemplate(transactionManager);
        for (String paymentId : expired) {
            try {
                transactions.executeWithoutResult(status -> expireUnpaid(paymentId, now));
            } catch (RuntimeException error) {
                LoggerFactory.getLogger(OrderService.class).warn("支付单关单未确认，保留库存，稍后重试：{}", paymentId);
            }
        }
    }

    private List<OrderLine> lockAndValidate(List<RequestedLine> requested) {
        List<RequestedLine> sorted = requested.stream().sorted((a, b) -> a.productId().compareTo(b.productId())).toList();
        Map<String, CatalogLine> products = new LinkedHashMap<>();
        for (RequestedLine request : sorted) {
            List<CatalogLine> found = db.query("SELECT p.id,p.merchant_id,m.name,p.name,p.description,p.price_cents,p.stock,p.reserved,m.business_status FROM merchant_product p JOIN merchant m ON m.id=p.merchant_id WHERE p.id=? AND p.available=TRUE AND m.audit_status='已通过' FOR UPDATE",
                (rs, rowNum) -> new CatalogLine(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getLong(6), rs.getInt(7), rs.getInt(8), rs.getString(9)), request.productId());
            if (found.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "商品已下架或不存在，请刷新菜单");
            CatalogLine product = found.get(0);
            if (!"营业中".equals(product.businessStatus())) throw new ResponseStatusException(HttpStatus.CONFLICT, product.merchantName() + " 当前已打烊");
            if (product.stock() - product.reserved() < request.quantity()) throw new ResponseStatusException(HttpStatus.CONFLICT, product.productName() + " 库存不足");
            products.put(product.productId(), product);
        }
        return requested.stream().map(line -> new OrderLine(products.get(line.productId()), line.quantity())).toList();
    }

    private static List<RequestedLine> parseItems(Object value) {
        if (!(value instanceof List<?> raw) || raw.isEmpty() || raw.size() > 50) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择 1 至 50 种餐品");
        Map<String, Integer> merged = new LinkedHashMap<>();
        for (Object item : raw) {
            if (!(item instanceof Map<?, ?> map)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "购物车餐品格式无效");
            String id = map.get("productId") == null ? "" : map.get("productId").toString().trim();
            int quantity;
            try { quantity = Integer.parseInt(String.valueOf(map.get("quantity"))); }
            catch (Exception exception) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "餐品数量无效"); }
            if (id.isBlank() || quantity < 1 || quantity > 99) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "餐品或数量无效");
            merged.merge(id, quantity, Math::addExact);
            if (merged.get(id) > 99) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "单种餐品数量不能超过 99 份");
        }
        return merged.entrySet().stream().map(e -> new RequestedLine(e.getKey(), e.getValue())).toList();
    }

    @Transactional
    public void releaseReservations(String orderId) {
        List<Map<String, Object>> items = db.queryForList("SELECT product_id,quantity FROM user_order_item WHERE order_id=?", orderId);
        for (Map<String, Object> item : items) {
            db.update("UPDATE merchant_product SET reserved=GREATEST(0,reserved-?) WHERE id=?", item.get("quantity"), item.get("product_id"));
        }
    }

    private Map<String, Object> paymentRow(long userId, String paymentId, boolean lock) {
        String sql = "SELECT * FROM payment_order WHERE id=? AND user_id=?" + (lock ? " FOR UPDATE" : "");
        List<Map<String, Object>> rows = db.queryForList(sql, paymentId, userId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "支付单不存在");
        return rows.get(0);
    }

    private static String text(Map<String, Object> body, String key) { return body.get(key) == null ? "" : body.get(key).toString().trim(); }
    private static String fingerprint(String addressId, List<RequestedLine> lines) {
        try {
            String canonical = addressId + "|" + lines.stream().sorted((a, b) -> a.productId().compareTo(b.productId())).map(x -> x.productId() + ":" + x.quantity()).reduce((a, b) -> a + "|" + b).orElse("");
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }
    private static String limit(String value, int length) { return value.length() <= length ? value : value.substring(0, length); }
}

package com.campusfood.user;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class CatalogService {
    private final JdbcTemplate db;
    @Value("${app.location-expiry-seconds:60}") private int locationExpirySeconds;
    @Value("${app.payment.mode:disabled}") private String paymentMode;
    private final WechatConfigurationService wechatConfiguration;
    @Value("${app.wechat.mock-login:false}") private boolean mockLogin;
    @Value("${app.unpaid-timeout-minutes:30}") private int unpaidTimeoutMinutes;

    public CatalogService(JdbcTemplate db, WechatConfigurationService wechatConfiguration) {
        this.db = db;
        this.wechatConfiguration = wechatConfiguration;
    }

    public Map<String, Object> config() {
        var config = db.queryForMap("SELECT fee_cents,regular_batch,peak_batch,service_start,service_end,peak_start,peak_end,accept_timeout,reminder_interval FROM operations_config ORDER BY id DESC LIMIT 1");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deliveryFeeCents", ((Number) config.get("fee_cents")).longValue());
        result.put("regularBatchMinutes", ((Number) config.get("regular_batch")).intValue());
        result.put("peakBatchMinutes", ((Number) config.get("peak_batch")).intValue());
        result.put("serviceStart", config.get("service_start"));
        result.put("serviceEnd", config.get("service_end"));
        result.put("peakStart", config.get("peak_start"));
        result.put("peakEnd", config.get("peak_end"));
        result.put("acceptTimeoutMinutes", ((Number) config.get("accept_timeout")).intValue());
        result.put("reminderIntervalMinutes", ((Number) config.get("reminder_interval")).intValue());
        result.put("locationExpirySeconds", locationExpirySeconds);
        result.put("unpaidTimeoutMinutes", unpaidTimeoutMinutes);
        result.put("paymentMode", "wechat".equals(paymentMode) && wechatConfiguration.currentPaymentVersion() == null
            ? "disabled" : paymentMode);
        result.put("mockLogin", mockLogin);
        result.put("appId", wechatConfiguration.currentAppId());
        result.put("schoolName", "示例大学");
        return result;
    }

    public record Photo(byte[] bytes, String mediaType) {}

    public Photo photo(boolean product, String id) {
        String table = product ? "merchant_product_photo" : "merchant_store_photo";
        if (!photoTableExists(table)) throw photoMissing();
        String sql = product ? """
            SELECT x.image_data,x.media_type FROM merchant_product_photo x
            JOIN merchant_product p ON p.id=x.product_id JOIN merchant m ON m.id=p.merchant_id
            WHERE p.id=? AND p.available=TRUE AND m.audit_status='已通过'
            """ : """
            SELECT x.image_data,x.media_type FROM merchant_store_photo x JOIN merchant m ON m.id=x.merchant_id
            WHERE m.id=? AND m.audit_status='已通过'
            """;
        Photo photo = db.query(sql, rs -> rs.next() ? new Photo(rs.getBytes(1), rs.getString(2)) : null, id);
        if (photo == null || !List.of("image/png", "image/jpeg").contains(photo.mediaType())) throw photoMissing();
        return photo;
    }

    private boolean photoTableExists(String table) {
        return Boolean.TRUE.equals(db.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name=?)
            """, Boolean.class, table));
    }

    private static org.springframework.web.server.ResponseStatusException photoMissing() {
        return new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "图片不存在");
    }

    public List<Map<String, Object>> catalog(String keyword, String category, String merchantId) {
        String term = keyword == null ? "" : keyword.trim();
        String categoryFilter = category == null || category.isBlank() || category.equals("全部") ? "" : category.trim();
        String storePhoto = photoTableExists("merchant_store_photo")
            ? "EXISTS(SELECT 1 FROM merchant_store_photo x WHERE x.merchant_id=m.id)" : "FALSE";
        String productPhoto = photoTableExists("merchant_product_photo")
            ? "EXISTS(SELECT 1 FROM merchant_product_photo x WHERE x.product_id=p.id)" : "FALSE";
        String sql = "SELECT " + storePhoto + " AS store_photo," + productPhoto + " AS product_photo,m.id AS merchant_id,m.name AS merchant_name,m.address AS merchant_address,m.business_status," +
            "p.id AS product_id,p.name AS product_name,p.description,p.category,p.price_cents,p.stock,p.reserved,p.artwork,p.color,p.available " +
            "FROM merchant m LEFT JOIN merchant_product p ON p.merchant_id=m.id AND p.available=TRUE " +
            "WHERE m.audit_status='已通过' AND (?='' OR m.id=?) AND (?='' OR m.name LIKE ? OR p.name LIKE ?) AND (?='' OR p.category=?) " +
            "ORDER BY m.id,p.display_order,p.name";
        String like = "%" + term + "%";
        List<Map<String, Object>> rows = db.queryForList(sql, merchantId, merchantId, term, like, like, categoryFilter, categoryFilter);
        long feePerMerchant = ((Number) config().get("deliveryFeeCents")).longValue();
        Map<String, Map<String, Object>> grouped = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String id = row.get("merchant_id").toString();
            Map<String, Object> merchant = grouped.computeIfAbsent(id, ignored -> {
                Map<String, Object> value = new LinkedHashMap<>();
                value.put("id", id);
                value.put("name", row.get("merchant_name"));
                value.put("address", row.get("merchant_address"));
                value.put("businessStatus", row.get("business_status"));
                value.put("open", "营业中".equals(row.get("business_status")));
                value.put("deliveryFeeCents", feePerMerchant);
                if (hasPhoto(row.get("store_photo"))) value.put("photoPath", "/catalog/merchants/" + id + "/photo");
                value.put("products", new ArrayList<Map<String, Object>>());
                return value;
            });
            if (row.get("product_id") == null) continue;
            Map<String, Object> product = new LinkedHashMap<>();
            product.put("id", row.get("product_id"));
            if (hasPhoto(row.get("product_photo"))) {
                product.put("photoPath", "/catalog/products/" + row.get("product_id") + "/photo");
            }
            product.put("name", row.get("product_name"));
            product.put("description", row.get("description"));
            product.put("category", row.get("category"));
            product.put("priceCents", ((Number) row.get("price_cents")).longValue());
            product.put("stock", ((Number) row.get("stock")).intValue());
            product.put("reserved", ((Number) row.get("reserved")).intValue());
            product.put("availableStock", Math.max(0, ((Number) row.get("stock")).intValue() - ((Number) row.get("reserved")).intValue()));
            product.put("artwork", row.get("artwork"));
            product.put("color", row.get("color"));
            product.put("available", Boolean.TRUE.equals(row.get("available")));
            @SuppressWarnings("unchecked") List<Map<String, Object>> products = (List<Map<String, Object>>) merchant.get("products");
            products.add(product);
        }
        return new ArrayList<>(grouped.values());
    }
    private static boolean hasPhoto(Object value) {
        return Boolean.TRUE.equals(value) || value instanceof Number number && number.intValue() == 1;
    }
}

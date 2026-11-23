package com.campusfood.merchant.service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ProductService {
    private final JdbcTemplate db;
    private final FoodCategoryService categories;

    public ProductService(JdbcTemplate db, FoodCategoryService categories) {
        this.db = db;
        this.categories = categories;
    }

    public List<Map<String, Object>> list(String merchant) {
        return db.queryForList("""
            SELECT p.id,p.name,p.category,p.description,p.price_cents AS priceCents,p.stock,p.reserved,p.available,
                   GREATEST(0,p.stock-p.reserved) AS availableStock,
                   EXISTS(SELECT 1 FROM merchant_product_photo photo WHERE photo.product_id=p.id) AS hasPhoto
            FROM merchant_product p WHERE p.merchant_id=? AND p.deleted_at IS NULL
            ORDER BY p.display_order,p.updated_at DESC,p.id
            """, merchant);
    }

    /** 管理商品目录包含已下架的商品。 */
    public Map<String, Object> adminCatalog(String keyword, String merchantId, String availability,
                                            int requestedPage, int requestedPageSize) {
        int page = Math.max(1, requestedPage);
        int pageSize = Math.min(100, Math.max(1, requestedPageSize));
        String search = keyword == null ? "" : keyword.trim();
        if (search.length() > 80) search = search.substring(0, 80);
        String storeId = merchantId == null ? "" : merchantId.trim();
        String status = availability == null ? "" : availability.trim();

        StringBuilder conditions = new StringBuilder(" WHERE p.deleted_at IS NULL AND m.deleted_at IS NULL");
        java.util.List<Object> parameters = new java.util.ArrayList<>();
        if (!search.isEmpty()) {
            conditions.append(" AND (p.id LIKE ? OR p.name LIKE ? OR p.category LIKE ? OR m.name LIKE ?)");
            String pattern = "%" + search + "%";
            parameters.add(pattern);
            parameters.add(pattern);
            parameters.add(pattern);
            parameters.add(pattern);
        }
        if (!storeId.isEmpty()) {
            conditions.append(" AND p.merchant_id=?");
            parameters.add(storeId);
        }
        if ("on".equals(status)) conditions.append(" AND p.available=TRUE");
        if ("off".equals(status)) conditions.append(" AND p.available=FALSE");

        Long total = db.queryForObject("SELECT COUNT(*) FROM merchant_product p JOIN merchant m ON m.id=p.merchant_id" + conditions,
            Long.class, parameters.toArray());
        String query = """
            SELECT p.id,p.merchant_id AS merchantId,m.name AS merchantName,p.name,p.category,p.description,
                   p.price_cents AS priceCents,p.stock,p.reserved,GREATEST(0,p.stock-p.reserved) AS availableStock,
                   p.sold_count AS soldCount,p.available,
                   EXISTS(SELECT 1 FROM merchant_product_photo photo WHERE photo.product_id=p.id) AS hasPhoto,
                   p.updated_at AS updatedAt
            FROM merchant_product p JOIN merchant m ON m.id=p.merchant_id
            """ + conditions + " ORDER BY p.updated_at DESC,p.display_order ASC,p.id ASC LIMIT ? OFFSET ?";
        parameters.add(pageSize);
        parameters.add((long) (page - 1) * pageSize);
        List<Map<String, Object>> rows = db.queryForList(query, parameters.toArray());
        return Map.of("rows", rows, "total", total == null ? 0 : total, "page", page, "pageSize", pageSize);
    }

    /** 管理员查看商品原图，只读取商户服务保存的商品图片。 */
    public UploadedPhoto adminPhoto(String id) {
        return db.query("""
            SELECT x.image_data,x.media_type FROM merchant_product_photo x
            JOIN merchant_product p ON p.id=x.product_id JOIN merchant m ON m.id=p.merchant_id
            WHERE x.product_id=? AND p.deleted_at IS NULL AND m.deleted_at IS NULL
            """,
            (row, n) -> new UploadedPhoto(row.getBytes(1), row.getString(2)), id)
            .stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "商品图片不存在"));
    }

    public UploadedPhoto photo(String merchant, String id) {
        return db.query("""
            SELECT photo.image_data,photo.media_type FROM merchant_product_photo photo
            JOIN merchant_product p ON p.id=photo.product_id WHERE p.merchant_id=? AND p.id=? AND p.deleted_at IS NULL
            """, (row, n) -> new UploadedPhoto(row.getBytes(1), row.getString(2)), merchant, id)
            .stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "商品图片不存在"));
    }

    public List<Map<String, Object>> history(String merchant, String id) {
        requireProduct(merchant, id, false);
        return db.queryForList("""
            SELECT previous_stock AS previousStock,new_stock AS newStock,created_at AS createdAt
            FROM merchant_stock_event WHERE merchant_id=? AND product_id=? ORDER BY id DESC LIMIT 100
            """, merchant, id);
    }

    @Transactional
    public Map<String, String> save(String merchant, String id, Map<String, Object> body) {
        requireStore(merchant);
        return saveProduct(merchant, id, body);
    }

    @Transactional
    public Map<String, String> adminSave(String id, Map<String, Object> body) {
        String merchant = id == null ? Inputs.text(body, "merchantId", "所属商户", 32, true) : owner(id);
        requireStore(merchant);
        var fields = new java.util.LinkedHashMap<>(body);
        Object availability = id == null ? null : requireProduct(merchant, id, true).get("available");
        fields.put("available", Boolean.TRUE.equals(availability) || availability instanceof Number flag && flag.intValue() != 0);
        return saveProduct(merchant, id, fields);
    }

    private Map<String, String> saveProduct(String merchant, String id, Map<String, Object> body) {
        String name = Inputs.text(body, "name", "商品名称", 120, true);
        String category = Inputs.text(body, "category", "分类", 40, true);
        String description = Inputs.text(body, "description", "商品描述", 500, false);
        long price = Inputs.integer(body, "priceCents", "价格（分）", 1, 10_000_000);
        int stock = (int) Inputs.integer(body, "stock", "库存", 0, 1_000_000);
        boolean available = Inputs.flag(body, "available", "上下架状态");
        UploadedPhoto photo = UploadedPhoto.from(body, "商品图片");
        category = categories.requireForProduct(category);
        int previous = 0;
        if (id == null) {
            if (photo == null) throw Inputs.invalid("请上传一张商品图片");
            id = UUID.randomUUID().toString();
            db.update("""
                INSERT INTO merchant_product(id,merchant_id,name,category,description,price_cents,stock,available)
                VALUES(?,?,?,?,?,?,?,?)
                """, id, merchant, name, category, description, price, stock, available);
        } else {
            Map<String, Object> product = requireProduct(merchant, id, true);
            previous = ((Number) product.get("stock")).intValue();
            int reserved = ((Number) product.get("reserved")).intValue();
            // 同一商品行也由结算预占操作锁定，手动改库存不能覆盖待支付预占。
            if (stock < reserved) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "库存不能低于待支付预占数量 " + reserved);
            }
            if (previous != Inputs.integer(body, "previousStock", "编辑前库存", 0, Integer.MAX_VALUE)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "库存已变化，请刷新商品后再编辑");
            }
            db.update("""
                UPDATE merchant_product SET name=?,category=?,description=?,price_cents=?,stock=?,available=?
                WHERE merchant_id=? AND id=?
                """, name, category, description, price, stock, available, merchant, id);
        }
        if (photo != null) {
            // 商品和图片在同一事务保存，编辑未提交新图时保留原图。
            db.update("""
                INSERT INTO merchant_product_photo(product_id,image_data,media_type) VALUES(?,?,?)
                ON DUPLICATE KEY UPDATE image_data=?,media_type=?
                """, id, photo.data(), photo.mediaType(), photo.data(), photo.mediaType());
        }
        if (stock != previous) {
            db.update("INSERT INTO merchant_stock_event(merchant_id,product_id,previous_stock,new_stock) VALUES(?,?,?,?)",
                merchant, id, previous, stock);
        }
        return Map.of("id", id);
    }

    @Transactional
    public void availability(String merchant, String id, Map<String, Object> body) {
        requireStore(merchant);
        updateAvailability(merchant, id, body);
    }

    @Transactional
    public void adminAvailability(String id, Map<String, Object> body) {
        String merchant = owner(id);
        requireStore(merchant);
        updateAvailability(merchant, id, body);
    }

    private void updateAvailability(String merchant, String id, Map<String, Object> body) {
        requireProduct(merchant, id, true);
        db.update("UPDATE merchant_product SET available=? WHERE merchant_id=? AND id=?",
            Inputs.flag(body, "available", "上下架状态"), merchant, id);
    }

    @Transactional
    public void adminDelete(String id) {
        String merchant = owner(id);
        requireStore(merchant);
        requireProduct(merchant, id, true);
        // 保留商品编号、预占库存与历史记录，供已创建的订单和退款继续处理。
        db.update("UPDATE merchant_product SET available=FALSE,deleted_at=CURRENT_TIMESTAMP WHERE id=?", id);
    }

    private String owner(String id) {
        return db.query("SELECT merchant_id FROM merchant_product WHERE id=? AND deleted_at IS NULL",
            (row, n) -> row.getString(1), id).stream().findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "商品不存在"));
    }

    private void requireStore(String id) {
        if (db.queryForList("""
            SELECT id FROM merchant WHERE id=? AND audit_status='APPROVED' AND deleted_at IS NULL FOR SHARE
            """, String.class, id).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "商户已删除或尚未通过审核");
        }
    }

    private Map<String, Object> requireProduct(String merchant, String id, boolean lock) {
        String sql = "SELECT stock,reserved,available FROM merchant_product WHERE merchant_id=? AND id=? AND deleted_at IS NULL";
        return db.queryForList(lock ? sql + " FOR UPDATE" : sql, merchant, id).stream().findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "商品不存在"));
    }
}

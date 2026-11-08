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

    public ProductService(JdbcTemplate db) {
        this.db = db;
    }

    public List<Map<String, Object>> list(String merchant) {
        return db.queryForList("""
            SELECT id,name,category,description,price_cents AS priceCents,stock,reserved,available,
                   GREATEST(0,stock-reserved) AS availableStock
            FROM merchant_product WHERE merchant_id=? ORDER BY display_order,updated_at DESC,id
            """, merchant);
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
        String name = Inputs.text(body, "name", "商品名称", 120, true);
        String category = Inputs.text(body, "category", "分类", 40, true);
        String description = Inputs.text(body, "description", "商品描述", 500, false);
        long price = Inputs.integer(body, "priceCents", "价格（分）", 1, 10_000_000);
        int stock = (int) Inputs.integer(body, "stock", "库存", 0, 1_000_000);
        boolean available = Inputs.flag(body, "available", "上下架状态");
        int previous = 0;
        if (id == null) {
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
        if (stock != previous) {
            db.update("INSERT INTO merchant_stock_event(merchant_id,product_id,previous_stock,new_stock) VALUES(?,?,?,?)",
                merchant, id, previous, stock);
        }
        return Map.of("id", id);
    }

    public void availability(String merchant, String id, Map<String, Object> body) {
        requireProduct(merchant, id, false);
        db.update("UPDATE merchant_product SET available=? WHERE merchant_id=? AND id=?",
            Inputs.flag(body, "available", "上下架状态"), merchant, id);
    }

    private Map<String, Object> requireProduct(String merchant, String id, boolean lock) {
        String sql = "SELECT stock,reserved FROM merchant_product WHERE merchant_id=? AND id=?";
        return db.queryForList(lock ? sql + " FOR UPDATE" : sql, merchant, id).stream().findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "商品不存在"));
    }
}

package com.campusfood.merchant.service;

import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** 维护一级食品分类，商品关联由数据库约束保障。 */
@Service
public class FoodCategoryService {
    private final JdbcTemplate db;

    public FoodCategoryService(JdbcTemplate db) {
        this.db = db;
    }

    public record Category(long id, String name, long productCount) {}

    public List<Category> list() {
        return db.query("""
            SELECT c.id,c.name,COUNT(p.id) AS product_count
            FROM food_category c LEFT JOIN merchant_product p ON p.category=c.name
            GROUP BY c.id,c.name ORDER BY c.id
            """, (row, index) -> new Category(row.getLong(1), row.getString(2), row.getLong(3)));
    }

    public List<String> names() {
        return db.queryForList("SELECT name FROM food_category WHERE TRIM(name)<>'' ORDER BY id", String.class);
    }

    public void create(String name) {
        try {
            db.update("INSERT INTO food_category(name) VALUES (?)", validate(name));
        } catch (DuplicateKeyException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该分类名称已存在");
        }
    }

    public void rename(long id, String name) {
        try {
            requireChanged(db.update("UPDATE food_category SET name=? WHERE id=?", validate(name), id));
        } catch (DuplicateKeyException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该分类名称已存在");
        }
    }

    public void delete(long id) {
        try {
            requireChanged(db.update("DELETE FROM food_category WHERE id=?", id));
        } catch (DataIntegrityViolationException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "请先将关联商品移到其他分类");
        }
    }

    /** 保存商品期间锁定分类，避免分类改名或删除与商品写入冲突。 */
    String requireForProduct(String name) {
        return db.query("SELECT name FROM food_category WHERE name=? FOR SHARE",
            (row, index) -> row.getString(1), name).stream().findFirst()
            .orElseThrow(() -> Inputs.invalid("食品分类已变更，请刷新后重新选择"));
    }

    private static String validate(String name) {
        String normalized = name == null ? "" : name.strip();
        if (normalized.isEmpty() || normalized.length() > 40 || normalized.equals("全部")) {
            throw Inputs.invalid("分类名称须为 1 至 40 个字符，且不能使用「全部」");
        }
        return normalized;
    }

    private static void requireChanged(int rows) {
        if (rows == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "食品分类不存在");
    }
}

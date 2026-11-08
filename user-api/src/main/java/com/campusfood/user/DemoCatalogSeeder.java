package com.campusfood.user;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Inserts clearly labelled catalogue data only in the local profile. */
@Component
@Profile("local")
public class DemoCatalogSeeder {
    private final JdbcTemplate db;
    private volatile boolean complete;

    public DemoCatalogSeeder(JdbcTemplate db) { this.db = db; }

    @Scheduled(initialDelay = 1000, fixedDelay = 5000)
    public synchronized void seedWhenAdminSchemaIsReady() {
        if (complete) return;
        Integer ready = db.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='merchant'", Integer.class);
        if (ready == null || ready != 1) return;
        db.update("INSERT IGNORE INTO merchant(id,name,contact,phone,address,audit_status,business_status,order_count,joined_at,audit_reason) VALUES(?,?,?,?,?,?,?,?,?,NULL)",
            "M03", "巷口小碗饭", "演示商户", "000-000-0000", "校外示例街 1 号", "已通过", "营业中", 0, "2026-11-07 00:00");
        db.update("INSERT IGNORE INTO merchant(id,name,contact,phone,address,audit_status,business_status,order_count,joined_at,audit_reason) VALUES(?,?,?,?,?,?,?,?,?,NULL)",
            "M04", "禾风面馆", "演示商户", "000-000-0000", "校外示例街 2 号", "已通过", "营业中", 0, "2026-11-07 00:00");
        product("P-M03-001", "M03", "香煎鸡腿饭", "外酥里嫩的香煎鸡腿，搭配米饭与时蔬。", "米饭", 2000, 60, "rice", "#eaeedb", 1);
        product("P-M03-002", "M03", "照烧鸡肉饭", "照烧鸡肉、热米饭与时蔬。", "米饭", 1800, 48, "rice", "#f6ecdc", 2);
        product("P-M04-001", "M04", "招牌牛肉拌面", "手作面条搭配牛肉与香葱酱汁。", "面食", 1600, 60, "noodle", "#f5eadb", 1);
        product("P-M04-002", "M04", "香菇鸡汤面", "清鲜鸡汤、香菇与手作面条。", "面食", 1500, 40, "noodle", "#e9eddf", 2);
        complete = true;
    }

    private void product(String id, String merchantId, String name, String description, String category,
                         long priceCents, int stock, String artwork, String color, int order) {
        db.update("INSERT IGNORE INTO merchant_product(id,merchant_id,name,description,category,price_cents,stock,reserved,sold_count,artwork,color,available,display_order) VALUES(?,?,?,?,?,?,?,0,0,?,?,TRUE,?)",
            id, merchantId, name, description, category, priceCents, stock, artwork, color, order);
    }
}

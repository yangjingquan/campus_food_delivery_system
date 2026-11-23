ALTER TABLE merchant
    ADD COLUMN listed BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN deleted_at TIMESTAMP NULL DEFAULT NULL;

INSERT INTO admin_permission VALUES ('merchant.manage','维护商户资料'),('product.manage','维护商户商品');
INSERT INTO admin_role_permission VALUES
    ('super','merchant.manage'),('super','product.manage'),
    ('operations','merchant.manage'),('operations','product.manage');

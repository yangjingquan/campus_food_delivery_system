ALTER TABLE merchant
    ADD COLUMN order_code INT UNSIGNED NOT NULL AUTO_INCREMENT,
    ADD UNIQUE KEY uq_merchant_order_code (order_code);

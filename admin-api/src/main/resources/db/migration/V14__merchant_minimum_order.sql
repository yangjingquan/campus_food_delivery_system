ALTER TABLE merchant
    ADD COLUMN minimum_order_cents BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT chk_merchant_minimum_order CHECK (minimum_order_cents BETWEEN 0 AND 99999999999);

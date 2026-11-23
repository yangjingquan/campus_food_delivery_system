ALTER TABLE user_order_detail
    ADD COLUMN merchant_settlement_wait_minutes INT UNSIGNED NOT NULL DEFAULT 1440,
    ADD COLUMN rider_settlement_wait_minutes INT UNSIGNED NOT NULL DEFAULT 1440;

ALTER TABLE order_settlement_snapshot
    ADD COLUMN settlement_wait_minutes INT UNSIGNED NOT NULL DEFAULT 1440,
    ADD COLUMN eligible_at BIGINT;

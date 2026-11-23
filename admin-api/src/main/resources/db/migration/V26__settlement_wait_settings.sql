ALTER TABLE operations_config
    ADD COLUMN merchant_settlement_wait_minutes INT UNSIGNED NOT NULL DEFAULT 1440,
    ADD COLUMN rider_settlement_wait_minutes INT UNSIGNED NOT NULL DEFAULT 1440;

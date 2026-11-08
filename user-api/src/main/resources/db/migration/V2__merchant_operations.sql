ALTER TABLE user_refund_detail ADD COLUMN restore_stock BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE user_order_detail
    ADD COLUMN stock_restored BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN pickup_rider_id VARCHAR(32),
    ADD COLUMN arrived_at BIGINT,
    ADD COLUMN batch_alert_at BIGINT,
    ADD COLUMN batch_rule_id BIGINT,
    ADD INDEX idx_order_batch_alert(batch_alert_at);

ALTER TABLE user_order_detail
    ADD COLUMN operations_rule_id BIGINT,
    ADD COLUMN food_rate_snapshot DECIMAL(5,2),
    ADD COLUMN merchant_settled_cents BIGINT,
    ADD COLUMN merchant_settled_at BIGINT;

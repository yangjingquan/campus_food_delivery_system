ALTER TABLE payment_order
    ADD COLUMN wechat_config_version BIGINT,
    ADD COLUMN prepay_id VARCHAR(128),
    ADD COLUMN wechat_started BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN wechat_refund_last_submit_at BIGINT;

ALTER TABLE user_refund_detail
    ADD COLUMN wechat_next_at BIGINT,
    ADD COLUMN wechat_attempted BOOLEAN NOT NULL DEFAULT FALSE,
    ADD INDEX idx_wechat_refund_next (wechat_next_at);

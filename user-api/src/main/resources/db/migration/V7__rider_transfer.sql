CREATE TABLE rider_transfer_channel (
    withdrawal_id VARCHAR(64) PRIMARY KEY,
    config_version BIGINT NOT NULL,
    app_id VARCHAR(32) NOT NULL,
    merchant_no VARCHAR(32) NOT NULL,
    channel_state VARCHAR(32) NOT NULL DEFAULT 'SUBMITTING',
    package_info VARCHAR(2048),
    checked_at BIGINT NOT NULL DEFAULT 0,
    INDEX idx_rider_transfer_check (checked_at)
) ENGINE=InnoDB;

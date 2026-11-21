CREATE TABLE merchant_transfer_channel (
    withdrawal_id VARCHAR(64) PRIMARY KEY,
    config_version BIGINT NOT NULL,
    app_id VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    merchant_no VARCHAR(32) NOT NULL,
    recipient_open_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    channel_state ENUM('PREPARING','SUBMITTING','UNKNOWN','ACCEPTED','PROCESSING',
        'WAIT_USER_CONFIRM','TRANSFERING','SUCCESS','FAIL','CANCELING','CANCELLED')
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    package_info VARCHAR(2048),
    checked_at BIGINT NOT NULL,
    CHECK (channel_state <> ''),
    FOREIGN KEY (withdrawal_id) REFERENCES withdrawal_request(id)
) ENGINE=InnoDB;

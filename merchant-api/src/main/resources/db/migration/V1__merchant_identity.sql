CREATE TABLE merchant_identity (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    app_id CHAR(18) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    open_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    merchant_id VARCHAR(32) UNIQUE,
    UNIQUE KEY uq_merchant_wechat (app_id, open_id),
    FOREIGN KEY (merchant_id) REFERENCES merchant(id)
) ENGINE=InnoDB;

CREATE TABLE merchant_session (
    token_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    identity_id BIGINT NOT NULL,
    expires_at BIGINT NOT NULL,
    INDEX idx_merchant_session_expiry (expires_at),
    FOREIGN KEY (identity_id) REFERENCES merchant_identity(id)
) ENGINE=InnoDB;

CREATE TABLE merchant_stock_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    merchant_id VARCHAR(32) NOT NULL,
    product_id VARCHAR(36) NOT NULL,
    previous_stock INT NOT NULL,
    new_stock INT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_merchant_stock_event (merchant_id, product_id, id)
) ENGINE=InnoDB;

CREATE TABLE merchant_pickup_ticket (
    order_id VARCHAR(64) PRIMARY KEY,
    token CHAR(22) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (order_id) REFERENCES business_order(id)
) ENGINE=InnoDB;

CREATE TABLE merchant_print_job (
    id VARCHAR(36) PRIMARY KEY,
    merchant_id VARCHAR(32) NOT NULL,
    order_id VARCHAR(64) NOT NULL,
    request_key VARCHAR(100) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'waiting',
    provider_id VARCHAR(128) NOT NULL DEFAULT '',
    message VARCHAR(255) NOT NULL DEFAULT '',
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    UNIQUE KEY uk_print_request (merchant_id, request_key),
    INDEX idx_print_worker (status, created_at),
    INDEX idx_print_order (merchant_id, order_id, created_at)
) ENGINE=InnoDB;

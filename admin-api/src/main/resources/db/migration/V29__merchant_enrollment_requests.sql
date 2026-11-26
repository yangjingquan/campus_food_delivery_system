INSERT INTO configuration_lock(name) VALUES ('merchant_enrollment');

CREATE TABLE merchant_enrollment_request (
    identity_id BIGINT PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    contact VARCHAR(80) NOT NULL,
    phone VARCHAR(32) NOT NULL,
    address VARCHAR(255) NOT NULL,
    image_data MEDIUMBLOB NOT NULL,
    media_type VARCHAR(20) NOT NULL,
    status ENUM('PENDING_REVIEW','APPROVED','REJECTED') CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason VARCHAR(500),
    submitted_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    merchant_id VARCHAR(32),
    FOREIGN KEY (merchant_id) REFERENCES merchant(id),
    INDEX idx_merchant_enrollment_status (status, submitted_at)
) ENGINE=InnoDB;

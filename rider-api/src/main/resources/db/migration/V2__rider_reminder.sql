CREATE TABLE rider_reminder_subscription (
    rider_id VARCHAR(32) PRIMARY KEY,
    template_id VARCHAR(128) NOT NULL,
    remaining INT NOT NULL DEFAULT 0,
    last_batch_at BIGINT NOT NULL DEFAULT 0,
    last_status VARCHAR(40),
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (rider_id) REFERENCES rider_identity(id)
) ENGINE=InnoDB;

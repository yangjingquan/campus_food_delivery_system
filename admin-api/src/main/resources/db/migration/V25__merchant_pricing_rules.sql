ALTER TABLE operations_config
    ADD COLUMN fee_bps INT,
    ADD COLUMN food_fixed_cents INT,
    ADD COLUMN delivery_fixed_cents INT,
    ADD COLUMN pricing_versioned BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE merchant_pricing_rule (
    merchant_id VARCHAR(32) PRIMARY KEY,
    version BIGINT NOT NULL,
    enabled BOOLEAN NOT NULL,
    fee_mode ENUM('FIXED','PERCENTAGE') NOT NULL,
    fee_value INT NOT NULL,
    food_mode ENUM('FIXED','PERCENTAGE') NOT NULL,
    food_value INT NOT NULL,
    delivery_mode ENUM('FIXED','PERCENTAGE') NOT NULL,
    delivery_value INT NOT NULL,
    updated_by VARCHAR(80) NOT NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (merchant_id) REFERENCES merchant(id),
    CHECK (fee_value BETWEEN 0 AND IF(fee_mode='FIXED',2147483647,10000) AND
           food_value BETWEEN 0 AND IF(food_mode='FIXED',2147483647,10000) AND
           delivery_value BETWEEN 0 AND IF(delivery_mode='FIXED',2147483647,10000))
) ENGINE=InnoDB;

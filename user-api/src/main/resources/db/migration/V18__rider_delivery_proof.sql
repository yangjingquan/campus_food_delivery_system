CREATE TABLE rider_delivery_proof (
    order_id VARCHAR(64) PRIMARY KEY,
    rider_id VARCHAR(64) NOT NULL,
    photo_data MEDIUMBLOB NOT NULL,
    media_type VARCHAR(16) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_rider_delivery_proof_order FOREIGN KEY (order_id) REFERENCES business_order(id)
) ENGINE=InnoDB;

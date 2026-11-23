CREATE TABLE order_pricing_rule (
    order_id VARCHAR(64) PRIMARY KEY,
    source ENUM('DEFAULT','MERCHANT') NOT NULL,
    version BIGINT NOT NULL,
    fee_mode ENUM('FIXED','PERCENTAGE') NOT NULL,
    fee_value INT NOT NULL,
    food_mode ENUM('FIXED','PERCENTAGE') NOT NULL,
    food_value INT NOT NULL,
    delivery_mode ENUM('FIXED','PERCENTAGE') NOT NULL,
    delivery_value INT NOT NULL,
    FOREIGN KEY (order_id) REFERENCES business_order(id)
) ENGINE=InnoDB;

CREATE TABLE order_settlement_snapshot (
    order_id VARCHAR(64) NOT NULL,
    event_type VARCHAR(40) NOT NULL,
    account_id VARCHAR(64) NOT NULL,
    rule_source ENUM('DEFAULT','MERCHANT','LEGACY') NOT NULL,
    rule_version BIGINT NOT NULL,
    rules JSON NOT NULL,
    food_base_cents BIGINT NOT NULL,
    delivery_base_cents BIGINT NOT NULL,
    original_delivery_cents BIGINT NOT NULL,
    food_refunded_cents BIGINT NOT NULL,
    delivery_refunded_cents BIGINT NOT NULL,
    food_commission_cents BIGINT NOT NULL,
    delivery_commission_cents BIGINT NOT NULL,
    rider_calculation_commission_cents BIGINT NOT NULL,
    rider_due_cents BIGINT NOT NULL,
    amount_cents BIGINT NOT NULL,
    settled_at BIGINT NOT NULL,
    PRIMARY KEY (order_id,event_type),
    FOREIGN KEY (order_id) REFERENCES business_order(id)
) ENGINE=InnoDB;

ALTER TABLE refund_record
    ADD COLUMN delivery_refund_cents BIGINT NOT NULL DEFAULT 0,
    ADD CHECK (delivery_refund_cents BETWEEN 0 AND amount_cents);

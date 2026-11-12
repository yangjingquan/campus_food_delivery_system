ALTER TABLE user_order_detail
    ADD COLUMN picked_up_at BIGINT,
    ADD COLUMN rider_commission_cents BIGINT,
    ADD COLUMN rider_settled_at BIGINT,
    ADD INDEX idx_order_rider (pickup_rider_id, rider_settled_at);

-- 已领取的历史订单使用成交时的配置版本补齐应得金额。
UPDATE user_order_detail d
JOIN business_order o ON o.id=d.order_id
LEFT JOIN operations_config c ON c.id=d.operations_rule_id
SET d.rider_commission_cents=ROUND(o.delivery_cents * COALESCE(c.delivery_rate,
    (SELECT delivery_rate FROM operations_config ORDER BY id DESC LIMIT 1)) / 100)
WHERE d.pickup_rider_id IS NOT NULL;

CREATE TABLE delivery_destination (
    building VARCHAR(80) PRIMARY KEY,
    latitude DECIMAL(10,7) NOT NULL,
    longitude DECIMAL(10,7) NOT NULL,
    CHECK (latitude BETWEEN -90 AND 90 AND longitude BETWEEN -180 AND 180)
) ENGINE=InnoDB;

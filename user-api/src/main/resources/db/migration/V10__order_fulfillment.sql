-- 历史订单和序列默认为外卖，保留已分配的取餐号及当日计数。
ALTER TABLE business_order
    ADD COLUMN fulfillment_type ENUM('delivery','pickup') NOT NULL DEFAULT 'delivery',
    DROP INDEX uq_order_pickup_number,
    ADD UNIQUE KEY uq_order_pickup_number (merchant_id, pickup_date, fulfillment_type, pickup_number);

ALTER TABLE merchant_daily_pickup_sequence
    ADD COLUMN fulfillment_type ENUM('delivery','pickup') NOT NULL DEFAULT 'delivery',
    DROP PRIMARY KEY,
    ADD PRIMARY KEY (merchant_id, pickup_date, fulfillment_type);

ALTER TABLE user_order_detail ADD COLUMN pickup_address VARCHAR(255);

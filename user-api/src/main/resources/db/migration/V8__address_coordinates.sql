-- 用户选点坐标使用 GCJ-02；历史地址保留为空，用户重新选点后补齐。
ALTER TABLE user_address
    ADD COLUMN latitude DECIMAL(10,7) NULL,
    ADD COLUMN longitude DECIMAL(10,7) NULL,
    ADD CONSTRAINT chk_address_coordinates CHECK (
        (latitude IS NULL AND longitude IS NULL) OR
        (latitude IS NOT NULL AND longitude IS NOT NULL
         AND latitude BETWEEN -90 AND 90 AND longitude BETWEEN -180 AND 180));

-- 下单时复制地址坐标，地址后续修改不改变已有订单的目的地。
ALTER TABLE user_order_detail
    ADD COLUMN destination_latitude DECIMAL(10,7) NULL,
    ADD COLUMN destination_longitude DECIMAL(10,7) NULL,
    ADD CONSTRAINT chk_order_destination_coordinates CHECK (
        (destination_latitude IS NULL AND destination_longitude IS NULL) OR
        (destination_latitude IS NOT NULL AND destination_longitude IS NOT NULL
         AND destination_latitude BETWEEN -90 AND 90 AND destination_longitude BETWEEN -180 AND 180));

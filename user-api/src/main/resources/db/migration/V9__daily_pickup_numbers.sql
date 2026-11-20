ALTER TABLE business_order
    ADD COLUMN pickup_date DATE NULL,
    ADD COLUMN pickup_number BIGINT NULL,
    ADD UNIQUE KEY uq_order_pickup_number (merchant_id, pickup_date, pickup_number);

CREATE TABLE merchant_daily_pickup_sequence (
    merchant_id VARCHAR(32) NOT NULL,
    pickup_date DATE NOT NULL,
    last_number BIGINT NOT NULL,
    PRIMARY KEY (merchant_id, pickup_date)
) ENGINE=InnoDB;

-- 历史订单优先按接单日志补号，缺少日志时用支付时间。按北京时间分日。
WITH acceptance AS (
    SELECT o.id, o.merchant_id,
        COALESCE((SELECT MIN(UNIX_TIMESTAMP(n.created_at)) FROM order_note n
            WHERE n.order_id=o.id AND n.note='待商户接单 → 商户出餐中'), o.paid_at DIV 1000) AS accepted_seconds
    FROM business_order o JOIN user_order_detail d ON d.order_id=o.id
    WHERE o.accepted=TRUE AND o.paid_at>0
), pickup_day AS (
    SELECT id, merchant_id, accepted_seconds,
        DATE(TIMESTAMPADD(SECOND, accepted_seconds+28800, '1970-01-01 00:00:00')) AS pickup_date
    FROM acceptance
), numbered AS (
    SELECT id, pickup_date,
        ROW_NUMBER() OVER (PARTITION BY merchant_id, pickup_date ORDER BY accepted_seconds,id) AS pickup_number
    FROM pickup_day
)
UPDATE business_order o JOIN numbered n ON n.id=o.id
SET o.pickup_date=n.pickup_date, o.pickup_number=n.pickup_number;

INSERT INTO merchant_daily_pickup_sequence (merchant_id, pickup_date, last_number)
SELECT merchant_id, pickup_date, MAX(pickup_number)
FROM business_order WHERE pickup_date IS NOT NULL
GROUP BY merchant_id, pickup_date;

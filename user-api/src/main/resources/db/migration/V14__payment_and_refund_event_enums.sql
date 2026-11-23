-- 先验证历史取值，遇到未知状态时中止，避免将现有业务记录改为其他状态。
CREATE TEMPORARY TABLE business_status_migration_check (valid_status BOOLEAN NOT NULL CHECK (valid_status = TRUE));
INSERT INTO business_status_migration_check
SELECT NOT EXISTS (
    SELECT 1 FROM payment_order WHERE CAST(status AS BINARY) NOT IN ('未支付', '支付成功', '已关闭', 'UNPAID', 'PAYMENT_SUCCEEDED', 'CLOSED')
) AND NOT EXISTS (
    SELECT 1 FROM user_after_sale_event WHERE CAST(status AS BINARY) NOT IN ('提交售后申请', '商户拒绝退款', '商户同意退款', '用户申请平台介入', '售后结束', '线上退款失败', '退款结果待核实', '线下已处理', '商户超时，平台介入', '商户确认退款', '商户接单超时，自动发起退款', '退款渠道未配置', '平台介入', '等待退款提交', '退款成功，订单已关闭', 'AFTER_SALE_REQUESTED', 'MERCHANT_REFUND_DECLINED', 'MERCHANT_REFUND_APPROVED', 'USER_ESCALATED', 'AFTER_SALE_ENDED', 'ONLINE_REFUND_FAILED', 'REFUND_VERIFICATION_REQUIRED', 'OFFLINE_RESOLVED', 'MERCHANT_TIMEOUT_ESCALATED', 'MERCHANT_REFUND_CONFIRMED', 'ORDER_TIMEOUT_REFUND_REQUESTED', 'REFUND_CHANNEL_UNCONFIGURED', 'PLATFORM_REVIEW', 'REFUND_QUEUED', 'REFUND_SUCCEEDED_ORDER_CLOSED')
);
DROP TEMPORARY TABLE business_status_migration_check;

UPDATE payment_order SET status = CASE CAST(status AS BINARY)
    WHEN '未支付' THEN 'UNPAID'
    WHEN '支付成功' THEN 'PAYMENT_SUCCEEDED'
    WHEN '已关闭' THEN 'CLOSED'
    ELSE status END
WHERE CAST(status AS BINARY) IN ('未支付', '支付成功', '已关闭');

UPDATE user_after_sale_event SET status = CASE CAST(status AS BINARY)
    WHEN '提交售后申请' THEN 'AFTER_SALE_REQUESTED'
    WHEN '商户拒绝退款' THEN 'MERCHANT_REFUND_DECLINED'
    WHEN '商户同意退款' THEN 'MERCHANT_REFUND_APPROVED'
    WHEN '用户申请平台介入' THEN 'USER_ESCALATED'
    WHEN '售后结束' THEN 'AFTER_SALE_ENDED'
    WHEN '线上退款失败' THEN 'ONLINE_REFUND_FAILED'
    WHEN '退款结果待核实' THEN 'REFUND_VERIFICATION_REQUIRED'
    WHEN '线下已处理' THEN 'OFFLINE_RESOLVED'
    WHEN '商户超时，平台介入' THEN 'MERCHANT_TIMEOUT_ESCALATED'
    WHEN '商户确认退款' THEN 'MERCHANT_REFUND_CONFIRMED'
    WHEN '商户接单超时，自动发起退款' THEN 'ORDER_TIMEOUT_REFUND_REQUESTED'
    WHEN '退款渠道未配置' THEN 'REFUND_CHANNEL_UNCONFIGURED'
    WHEN '平台介入' THEN 'PLATFORM_REVIEW'
    WHEN '等待退款提交' THEN 'REFUND_QUEUED'
    WHEN '退款成功，订单已关闭' THEN 'REFUND_SUCCEEDED_ORDER_CLOSED'
    ELSE status END
WHERE CAST(status AS BINARY) IN ('提交售后申请', '商户拒绝退款', '商户同意退款', '用户申请平台介入', '售后结束', '线上退款失败', '退款结果待核实', '线下已处理', '商户超时，平台介入', '商户确认退款', '商户接单超时，自动发起退款', '退款渠道未配置', '平台介入', '等待退款提交', '退款成功，订单已关闭');

ALTER TABLE payment_order
    MODIFY status ENUM('UNPAID', 'PAYMENT_SUCCEEDED', 'CLOSED') CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ADD CONSTRAINT chk_payment_order_status_code CHECK (status <> '');

ALTER TABLE user_after_sale_event
    MODIFY status ENUM('AFTER_SALE_REQUESTED', 'MERCHANT_REFUND_DECLINED', 'MERCHANT_REFUND_APPROVED', 'USER_ESCALATED', 'AFTER_SALE_ENDED', 'ONLINE_REFUND_FAILED', 'REFUND_VERIFICATION_REQUIRED', 'OFFLINE_RESOLVED', 'MERCHANT_TIMEOUT_ESCALATED', 'MERCHANT_REFUND_CONFIRMED', 'ORDER_TIMEOUT_REFUND_REQUESTED', 'REFUND_CHANNEL_UNCONFIGURED', 'PLATFORM_REVIEW', 'REFUND_QUEUED', 'REFUND_SUCCEEDED_ORDER_CLOSED') CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ADD CONSTRAINT chk_user_after_sale_event_status_code CHECK (status <> '');

UPDATE order_note
SET previous_status = CASE CAST(SUBSTRING_INDEX(note, ' → ', 1) AS BINARY)
    WHEN '待支付' THEN 'PENDING_PAYMENT'
    WHEN '待商户接单' THEN 'AWAITING_MERCHANT'
    WHEN '商户出餐中' THEN 'PREPARING_FOOD'
    WHEN '已到暂存点 / 待领取' THEN 'AWAITING_RIDER'
    WHEN '待自取' THEN 'AWAITING_PICKUP'
    WHEN '配送中' THEN 'DELIVERING'
    WHEN '已完成' THEN 'COMPLETED'
    WHEN '已关闭' THEN 'CLOSED'
    END,
    next_status = CASE CAST(SUBSTRING_INDEX(note, ' → ', -1) AS BINARY)
    WHEN '待支付' THEN 'PENDING_PAYMENT'
    WHEN '待商户接单' THEN 'AWAITING_MERCHANT'
    WHEN '商户出餐中' THEN 'PREPARING_FOOD'
    WHEN '已到暂存点 / 待领取' THEN 'AWAITING_RIDER'
    WHEN '待自取' THEN 'AWAITING_PICKUP'
    WHEN '配送中' THEN 'DELIVERING'
    WHEN '已完成' THEN 'COMPLETED'
    WHEN '已关闭' THEN 'CLOSED'
    END
WHERE actor_code IS NOT NULL AND note LIKE '% → %';
UPDATE order_note SET actor='',note='' WHERE previous_status IS NOT NULL AND next_status IS NOT NULL;

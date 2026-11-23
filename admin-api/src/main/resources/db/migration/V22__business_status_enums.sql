-- 先验证历史取值，遇到未知状态时中止，避免将现有业务记录改为其他状态。
CREATE TEMPORARY TABLE business_status_migration_check (valid_status BOOLEAN NOT NULL CHECK (valid_status = TRUE));
INSERT INTO business_status_migration_check
SELECT NOT EXISTS (
    SELECT 1 FROM business_order WHERE CAST(payment_status AS BINARY) NOT IN ('未支付', '支付成功', '本地模拟支付成功', '已关闭', 'UNPAID', 'PAYMENT_SUCCEEDED', 'MOCK_PAYMENT_SUCCEEDED', 'CLOSED')
) AND NOT EXISTS (
    SELECT 1 FROM business_order WHERE CAST(fulfill_status AS BINARY) NOT IN ('待支付', '待商户接单', '商户出餐中', '已到暂存点 / 待领取', '待自取', '配送中', '已完成', '已关闭', 'PENDING_PAYMENT', 'AWAITING_MERCHANT', 'PREPARING_FOOD', 'AWAITING_RIDER', 'AWAITING_PICKUP', 'DELIVERING', 'COMPLETED', 'CLOSED')
) AND NOT EXISTS (
    SELECT 1 FROM business_order WHERE CAST(refund_status AS BINARY) NOT IN ('', '无售后', '已结束', '退款成功', '线下已处理', '退款失败', '平台介入', '待商户处理', '退款处理中', '退款结果核实中', '等待退款提交', '拒绝退款', '协商中', 'NO_AFTER_SALE', 'AFTER_SALE_ENDED', 'REFUND_SUCCEEDED', 'OFFLINE_RESOLVED', 'REFUND_FAILED', 'PLATFORM_REVIEW', 'AWAITING_MERCHANT_DECISION', 'REFUND_PROCESSING', 'REFUND_VERIFYING', 'REFUND_QUEUED', 'REFUND_REJECTED', 'NEGOTIATING')
) AND NOT EXISTS (
    SELECT 1 FROM business_order WHERE CAST(settlement_status AS BINARY) NOT IN ('待支付', '待结算', '可提现', '已结算', '已提现', '已关闭', '售后暂缓', '待入账', 'PENDING_PAYMENT', 'PENDING_SETTLEMENT', 'WITHDRAWABLE', 'SETTLED', 'WITHDRAWN', 'CLOSED', 'SETTLEMENT_HELD', 'PENDING_CREDIT')
) AND NOT EXISTS (
    SELECT 1 FROM refund_record WHERE CAST(status AS BINARY) NOT IN ('已结束', '退款成功', '线下已处理', '退款失败', '平台介入', '待商户处理', '退款处理中', '退款结果核实中', '等待退款提交', '拒绝退款', '协商中', 'AFTER_SALE_ENDED', 'REFUND_SUCCEEDED', 'OFFLINE_RESOLVED', 'REFUND_FAILED', 'PLATFORM_REVIEW', 'AWAITING_MERCHANT_DECISION', 'REFUND_PROCESSING', 'REFUND_VERIFYING', 'REFUND_QUEUED', 'REFUND_REJECTED', 'NEGOTIATING')
) AND NOT EXISTS (
    SELECT 1 FROM merchant WHERE CAST(audit_status AS BINARY) NOT IN ('待审核', '已通过', '已驳回', 'PENDING_REVIEW', 'APPROVED', 'REJECTED')
) AND NOT EXISTS (
    SELECT 1 FROM merchant WHERE CAST(business_status AS BINARY) NOT IN ('休息中', '营业中', 'STORE_CLOSED', 'STORE_OPEN')
) AND NOT EXISTS (
    SELECT 1 FROM merchant_profile_revision WHERE CAST(status AS BINARY) NOT IN ('待审核', '已通过', '已驳回', 'PENDING_REVIEW', 'APPROVED', 'REJECTED')
) AND NOT EXISTS (
    SELECT 1 FROM rider WHERE CAST(audit_status AS BINARY) NOT IN ('待审核', '已通过', '已驳回', 'PENDING_REVIEW', 'APPROVED', 'REJECTED')
) AND NOT EXISTS (
    SELECT 1 FROM rider WHERE CAST(gender AS BINARY) NOT IN ('男', '女', '不便透露', 'MALE', 'FEMALE', 'UNDISCLOSED')
) AND NOT EXISTS (
    SELECT 1 FROM finance_account WHERE CAST(type AS BINARY) NOT IN ('骑手', '商户', '平台', 'RIDER', 'MERCHANT', 'PLATFORM')
) AND NOT EXISTS (
    SELECT 1 FROM finance_account WHERE CAST(status AS BINARY) NOT IN ('正常', '停用', '冻结', 'ACCOUNT_ACTIVE', 'ACCOUNT_DISABLED', 'ACCOUNT_FROZEN')
) AND NOT EXISTS (
    SELECT 1 FROM withdrawal_request WHERE CAST(applicant_type AS BINARY) NOT IN ('骑手', '商户', 'RIDER', 'MERCHANT')
);
DROP TEMPORARY TABLE business_status_migration_check;

UPDATE business_order SET payment_status = CASE CAST(payment_status AS BINARY)
    WHEN '未支付' THEN 'UNPAID'
    WHEN '支付成功' THEN 'PAYMENT_SUCCEEDED'
    WHEN '本地模拟支付成功' THEN 'MOCK_PAYMENT_SUCCEEDED'
    WHEN '已关闭' THEN 'CLOSED'
    ELSE payment_status END
WHERE CAST(payment_status AS BINARY) IN ('未支付', '支付成功', '本地模拟支付成功', '已关闭');

UPDATE business_order SET fulfill_status = CASE CAST(fulfill_status AS BINARY)
    WHEN '待支付' THEN 'PENDING_PAYMENT'
    WHEN '待商户接单' THEN 'AWAITING_MERCHANT'
    WHEN '商户出餐中' THEN 'PREPARING_FOOD'
    WHEN '已到暂存点 / 待领取' THEN 'AWAITING_RIDER'
    WHEN '待自取' THEN 'AWAITING_PICKUP'
    WHEN '配送中' THEN 'DELIVERING'
    WHEN '已完成' THEN 'COMPLETED'
    WHEN '已关闭' THEN 'CLOSED'
    ELSE fulfill_status END
WHERE CAST(fulfill_status AS BINARY) IN ('待支付', '待商户接单', '商户出餐中', '已到暂存点 / 待领取', '待自取', '配送中', '已完成', '已关闭');

UPDATE business_order SET refund_status = CASE CAST(refund_status AS BINARY)
    WHEN '' THEN 'NO_AFTER_SALE'
    WHEN '无售后' THEN 'NO_AFTER_SALE'
    WHEN '已结束' THEN 'AFTER_SALE_ENDED'
    WHEN '退款成功' THEN 'REFUND_SUCCEEDED'
    WHEN '线下已处理' THEN 'OFFLINE_RESOLVED'
    WHEN '退款失败' THEN 'REFUND_FAILED'
    WHEN '平台介入' THEN 'PLATFORM_REVIEW'
    WHEN '待商户处理' THEN 'AWAITING_MERCHANT_DECISION'
    WHEN '退款处理中' THEN 'REFUND_PROCESSING'
    WHEN '退款结果核实中' THEN 'REFUND_VERIFYING'
    WHEN '等待退款提交' THEN 'REFUND_QUEUED'
    WHEN '拒绝退款' THEN 'REFUND_REJECTED'
    WHEN '协商中' THEN 'NEGOTIATING'
    ELSE refund_status END
WHERE CAST(refund_status AS BINARY) IN ('', '无售后', '已结束', '退款成功', '线下已处理', '退款失败', '平台介入', '待商户处理', '退款处理中', '退款结果核实中', '等待退款提交', '拒绝退款', '协商中');

UPDATE business_order SET settlement_status = CASE CAST(settlement_status AS BINARY)
    WHEN '待支付' THEN 'PENDING_PAYMENT'
    WHEN '待结算' THEN 'PENDING_SETTLEMENT'
    WHEN '可提现' THEN 'WITHDRAWABLE'
    WHEN '已结算' THEN 'SETTLED'
    WHEN '已提现' THEN 'WITHDRAWN'
    WHEN '已关闭' THEN 'CLOSED'
    WHEN '售后暂缓' THEN 'SETTLEMENT_HELD'
    WHEN '待入账' THEN 'PENDING_CREDIT'
    ELSE settlement_status END
WHERE CAST(settlement_status AS BINARY) IN ('待支付', '待结算', '可提现', '已结算', '已提现', '已关闭', '售后暂缓', '待入账');

UPDATE refund_record SET status = CASE CAST(status AS BINARY)
    WHEN '已结束' THEN 'AFTER_SALE_ENDED'
    WHEN '退款成功' THEN 'REFUND_SUCCEEDED'
    WHEN '线下已处理' THEN 'OFFLINE_RESOLVED'
    WHEN '退款失败' THEN 'REFUND_FAILED'
    WHEN '平台介入' THEN 'PLATFORM_REVIEW'
    WHEN '待商户处理' THEN 'AWAITING_MERCHANT_DECISION'
    WHEN '退款处理中' THEN 'REFUND_PROCESSING'
    WHEN '退款结果核实中' THEN 'REFUND_VERIFYING'
    WHEN '等待退款提交' THEN 'REFUND_QUEUED'
    WHEN '拒绝退款' THEN 'REFUND_REJECTED'
    WHEN '协商中' THEN 'NEGOTIATING'
    ELSE status END
WHERE CAST(status AS BINARY) IN ('已结束', '退款成功', '线下已处理', '退款失败', '平台介入', '待商户处理', '退款处理中', '退款结果核实中', '等待退款提交', '拒绝退款', '协商中');

UPDATE merchant SET audit_status = CASE CAST(audit_status AS BINARY)
    WHEN '待审核' THEN 'PENDING_REVIEW'
    WHEN '已通过' THEN 'APPROVED'
    WHEN '已驳回' THEN 'REJECTED'
    ELSE audit_status END
WHERE CAST(audit_status AS BINARY) IN ('待审核', '已通过', '已驳回');

UPDATE merchant SET business_status = CASE CAST(business_status AS BINARY)
    WHEN '休息中' THEN 'STORE_CLOSED'
    WHEN '营业中' THEN 'STORE_OPEN'
    ELSE business_status END
WHERE CAST(business_status AS BINARY) IN ('休息中', '营业中');

UPDATE merchant_profile_revision SET status = CASE CAST(status AS BINARY)
    WHEN '待审核' THEN 'PENDING_REVIEW'
    WHEN '已通过' THEN 'APPROVED'
    WHEN '已驳回' THEN 'REJECTED'
    ELSE status END
WHERE CAST(status AS BINARY) IN ('待审核', '已通过', '已驳回');

UPDATE rider SET audit_status = CASE CAST(audit_status AS BINARY)
    WHEN '待审核' THEN 'PENDING_REVIEW'
    WHEN '已通过' THEN 'APPROVED'
    WHEN '已驳回' THEN 'REJECTED'
    ELSE audit_status END
WHERE CAST(audit_status AS BINARY) IN ('待审核', '已通过', '已驳回');

UPDATE rider SET gender = CASE CAST(gender AS BINARY)
    WHEN '男' THEN 'MALE'
    WHEN '女' THEN 'FEMALE'
    WHEN '不便透露' THEN 'UNDISCLOSED'
    ELSE gender END
WHERE CAST(gender AS BINARY) IN ('男', '女', '不便透露');

UPDATE finance_account SET type = CASE CAST(type AS BINARY)
    WHEN '骑手' THEN 'RIDER'
    WHEN '商户' THEN 'MERCHANT'
    WHEN '平台' THEN 'PLATFORM'
    ELSE type END
WHERE CAST(type AS BINARY) IN ('骑手', '商户', '平台');

UPDATE finance_account SET status = CASE CAST(status AS BINARY)
    WHEN '正常' THEN 'ACCOUNT_ACTIVE'
    WHEN '停用' THEN 'ACCOUNT_DISABLED'
    WHEN '冻结' THEN 'ACCOUNT_FROZEN'
    ELSE status END
WHERE CAST(status AS BINARY) IN ('正常', '停用', '冻结');

UPDATE withdrawal_request SET applicant_type = CASE CAST(applicant_type AS BINARY)
    WHEN '骑手' THEN 'RIDER'
    WHEN '商户' THEN 'MERCHANT'
    ELSE applicant_type END
WHERE CAST(applicant_type AS BINARY) IN ('骑手', '商户');

ALTER TABLE business_order
    MODIFY payment_status ENUM('UNPAID', 'PAYMENT_SUCCEEDED', 'MOCK_PAYMENT_SUCCEEDED', 'CLOSED') CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ADD CONSTRAINT chk_business_order_payment_status_code CHECK (payment_status <> ''),
    MODIFY fulfill_status ENUM('PENDING_PAYMENT', 'AWAITING_MERCHANT', 'PREPARING_FOOD', 'AWAITING_RIDER', 'AWAITING_PICKUP', 'DELIVERING', 'COMPLETED', 'CLOSED') CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ADD CONSTRAINT chk_business_order_fulfill_status_code CHECK (fulfill_status <> ''),
    MODIFY refund_status ENUM('NO_AFTER_SALE', 'AFTER_SALE_ENDED', 'REFUND_SUCCEEDED', 'OFFLINE_RESOLVED', 'REFUND_FAILED', 'PLATFORM_REVIEW', 'AWAITING_MERCHANT_DECISION', 'REFUND_PROCESSING', 'REFUND_VERIFYING', 'REFUND_QUEUED', 'REFUND_REJECTED', 'NEGOTIATING') CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'NO_AFTER_SALE',
    ADD CONSTRAINT chk_business_order_refund_status_code CHECK (refund_status <> ''),
    MODIFY settlement_status ENUM('PENDING_PAYMENT', 'PENDING_SETTLEMENT', 'WITHDRAWABLE', 'SETTLED', 'WITHDRAWN', 'CLOSED', 'SETTLEMENT_HELD', 'PENDING_CREDIT') CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PENDING_SETTLEMENT',
    ADD CONSTRAINT chk_business_order_settlement_status_code CHECK (settlement_status <> '');

ALTER TABLE refund_record
    MODIFY status ENUM('AFTER_SALE_ENDED', 'REFUND_SUCCEEDED', 'OFFLINE_RESOLVED', 'REFUND_FAILED', 'PLATFORM_REVIEW', 'AWAITING_MERCHANT_DECISION', 'REFUND_PROCESSING', 'REFUND_VERIFYING', 'REFUND_QUEUED', 'REFUND_REJECTED', 'NEGOTIATING') CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ADD CONSTRAINT chk_refund_record_status_code CHECK (status <> '');

ALTER TABLE merchant
    MODIFY audit_status ENUM('PENDING_REVIEW', 'APPROVED', 'REJECTED') CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ADD CONSTRAINT chk_merchant_audit_status_code CHECK (audit_status <> ''),
    MODIFY business_status ENUM('STORE_CLOSED', 'STORE_OPEN') CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ADD CONSTRAINT chk_merchant_business_status_code CHECK (business_status <> '');

ALTER TABLE merchant_profile_revision
    MODIFY status ENUM('PENDING_REVIEW', 'APPROVED', 'REJECTED') CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ADD CONSTRAINT chk_merchant_profile_revision_status_code CHECK (status <> '');

ALTER TABLE rider
    MODIFY audit_status ENUM('PENDING_REVIEW', 'APPROVED', 'REJECTED') CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ADD CONSTRAINT chk_rider_audit_status_code CHECK (audit_status <> ''),
    MODIFY gender ENUM('MALE', 'FEMALE', 'UNDISCLOSED') CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ADD CONSTRAINT chk_rider_gender_code CHECK (gender <> '');

ALTER TABLE finance_account
    MODIFY type ENUM('RIDER', 'MERCHANT', 'PLATFORM') CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ADD CONSTRAINT chk_finance_account_type_code CHECK (type <> ''),
    MODIFY status ENUM('ACCOUNT_ACTIVE', 'ACCOUNT_DISABLED', 'ACCOUNT_FROZEN') CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ADD CONSTRAINT chk_finance_account_status_code CHECK (status <> '');

ALTER TABLE withdrawal_request
    MODIFY applicant_type ENUM('RIDER', 'MERCHANT') CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ADD CONSTRAINT chk_withdrawal_request_applicant_type_code CHECK (applicant_type <> '');

-- 将固定代码和可变说明分开保存，保留原始说明和历史渠道详情。
ALTER TABLE business_order ADD COLUMN intervene_code ENUM('PICKUP_OVERDUE', 'USER_ESCALATED', 'REFUND_FAILED_OFFLINE_REQUIRED', 'MERCHANT_REFUND_TIMEOUT', 'REFUND_CHANNEL_UNCONFIGURED', 'REFUND_LIMIT_REACHED') CHARACTER SET ascii COLLATE ascii_bin;
UPDATE business_order SET intervene_code = CASE CAST(intervene_reason AS BINARY)
    WHEN '暂存点已跨两个配送批次仍未领取' THEN 'PICKUP_OVERDUE'
    WHEN '用户申请平台介入' THEN 'USER_ESCALATED'
    WHEN '退款失败，需线下核实' THEN 'REFUND_FAILED_OFFLINE_REQUIRED'
    WHEN '商户超过 3 小时未处理售后' THEN 'MERCHANT_REFUND_TIMEOUT'
    WHEN '退款渠道未配置，需线下核实' THEN 'REFUND_CHANNEL_UNCONFIGURED'
    WHEN '合并支付单退款次数达到微信侧限制' THEN 'REFUND_LIMIT_REACHED'
    END
WHERE intervene_reason IS NOT NULL;
UPDATE business_order SET intervene_reason=NULL WHERE intervene_code IS NOT NULL;

ALTER TABLE refund_record
    MODIFY operator VARCHAR(80) NULL,
    ADD COLUMN operator_code ENUM('USER_REQUEST', 'MERCHANT_ACTION', 'USER_ESCALATED', 'USER_ENDED', 'REFUND_CHANNEL', 'SYSTEM_ESCALATED', 'MERCHANT_ENDED', 'MERCHANT_NEGOTIATED', 'SYSTEM_TIMEOUT', 'OFFLINE_ADMIN') CHARACTER SET ascii COLLATE ascii_bin,
    ADD COLUMN channel_status_code ENUM('REFUND_SUBMISSION_PENDING', 'REFUND_NOT_STARTED', 'MERCHANT_DECLINED', 'REFUND_FAILED', 'REFUND_RESULT_UNKNOWN', 'OFFLINE_VERIFIED', 'REFUND_CHANNEL_UNCONFIGURED', 'REFUND_LIMIT_REACHED', 'REFUND_INTERVAL_LIMIT', 'WECHAT_REFUND_SUBMISSION_PENDING', 'MOCK_REFUND_PROCESSING', 'WECHAT_REFUND_CONFIRMATION_PENDING', 'WECHAT_REFUND_PROCESSING', 'WECHAT_REFUND_SUCCEEDED', 'MOCK_REFUND_SUCCEEDED', 'LEGACY_DETAIL') CHARACTER SET ascii COLLATE ascii_bin,
    ADD COLUMN channel_detail VARCHAR(160);
UPDATE refund_record
SET operator_code = CASE CAST(operator AS BINARY)
    WHEN '用户申请' THEN 'USER_REQUEST'
    WHEN '商户处理' THEN 'MERCHANT_ACTION'
    WHEN '用户申请平台介入' THEN 'USER_ESCALATED'
    WHEN '用户结束售后' THEN 'USER_ENDED'
    WHEN '退款渠道' THEN 'REFUND_CHANNEL'
    WHEN '系统超时介入' THEN 'SYSTEM_ESCALATED'
    WHEN '商户协商结束' THEN 'MERCHANT_ENDED'
    WHEN '商户协商' THEN 'MERCHANT_NEGOTIATED'
    WHEN '系统超时处理' THEN 'SYSTEM_TIMEOUT'
    WHEN '管理员线下核实' THEN 'OFFLINE_ADMIN'
    END,
    channel_status_code = COALESCE(CASE
    WHEN channel_status LIKE '已核实线下实际退款成功：%' THEN 'OFFLINE_VERIFIED'
    ELSE CASE CAST(channel_status AS BINARY)
    WHEN '等待退款受理' THEN 'REFUND_SUBMISSION_PENDING'
    WHEN '未发起' THEN 'REFUND_NOT_STARTED'
    WHEN '商户拒绝' THEN 'MERCHANT_DECLINED'
    WHEN '退款失败' THEN 'REFUND_FAILED'
    WHEN '结果未知' THEN 'REFUND_RESULT_UNKNOWN'
    WHEN '线上失败；线下已核实' THEN 'OFFLINE_VERIFIED'
    WHEN '渠道未配置' THEN 'REFUND_CHANNEL_UNCONFIGURED'
    WHEN '合并支付单退款次数达到上限' THEN 'REFUND_LIMIT_REACHED'
    WHEN '同一支付单退款间隔限制' THEN 'REFUND_INTERVAL_LIMIT'
    WHEN '等待微信退款受理' THEN 'WECHAT_REFUND_SUBMISSION_PENDING'
    WHEN '本地模拟退款处理中' THEN 'MOCK_REFUND_PROCESSING'
    WHEN '等待微信确认' THEN 'WECHAT_REFUND_CONFIRMATION_PENDING'
    WHEN '微信退款处理中' THEN 'WECHAT_REFUND_PROCESSING'
    WHEN '微信渠道确认退款成功' THEN 'WECHAT_REFUND_SUCCEEDED'
    WHEN '本地模拟退款成功；未调用微信支付' THEN 'MOCK_REFUND_SUCCEEDED'
    END END, 'LEGACY_DETAIL');
UPDATE refund_record SET operator=NULL WHERE operator_code IS NOT NULL;
UPDATE refund_record SET channel_detail=channel_status WHERE channel_status_code='LEGACY_DETAIL' OR channel_status LIKE '已核实线下实际退款成功：%';
ALTER TABLE refund_record
    DROP COLUMN channel_status,
    CHANGE channel_status_code channel_status ENUM('REFUND_SUBMISSION_PENDING', 'REFUND_NOT_STARTED', 'MERCHANT_DECLINED', 'REFUND_FAILED', 'REFUND_RESULT_UNKNOWN', 'OFFLINE_VERIFIED', 'REFUND_CHANNEL_UNCONFIGURED', 'REFUND_LIMIT_REACHED', 'REFUND_INTERVAL_LIMIT', 'WECHAT_REFUND_SUBMISSION_PENDING', 'MOCK_REFUND_PROCESSING', 'WECHAT_REFUND_CONFIRMATION_PENDING', 'WECHAT_REFUND_PROCESSING', 'WECHAT_REFUND_SUCCEEDED', 'MOCK_REFUND_SUCCEEDED', 'LEGACY_DETAIL') CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ADD CONSTRAINT chk_refund_record_channel_status_code CHECK (channel_status <> '');

ALTER TABLE withdrawal_request ADD COLUMN reason_code ENUM('WECHAT_FUNDS_INSUFFICIENT', 'SUBMISSION_BLOCKED', 'INVALID_APPLICATION_TIME', 'WECHAT_SUBMISSION_ERROR') CHARACTER SET ascii COLLATE ascii_bin;
UPDATE withdrawal_request SET reason_code = COALESCE(CASE CAST(reason AS BINARY)
    WHEN '微信转账提交异常：HTTP 403 / NOT_ENOUGH' THEN 'WECHAT_FUNDS_INSUFFICIENT'
    WHEN '审核通过，未发起转账：渠道准备失败，请检查签名材料及管理员服务' THEN 'SUBMISSION_BLOCKED'
    WHEN '审核通过，未发起转账：提现申请时间无效' THEN 'INVALID_APPLICATION_TIME'
    END,
    IF(reason LIKE '微信转账提交异常：%', 'WECHAT_SUBMISSION_ERROR', NULL))
WHERE reason IS NOT NULL;

ALTER TABLE rider ADD COLUMN availability ENUM('MIDDAY', 'EVENING', 'MIDDAY_AND_EVENING', 'CUSTOM') CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'CUSTOM';
UPDATE rider SET availability = COALESCE(CASE CAST(available_time AS BINARY)
    WHEN '午间 11:00–13:00' THEN 'MIDDAY'
    WHEN '晚间 17:00–19:00' THEN 'EVENING'
    WHEN '午间与晚间' THEN 'MIDDAY_AND_EVENING'
    WHEN '其他时间' THEN 'CUSTOM'
    END, 'CUSTOM');
UPDATE rider SET available_time = REGEXP_REPLACE(
    REPLACE(REPLACE(REPLACE(REGEXP_REPLACE(available_time, '^(午间|晚间)[[:space:]]+', ''), '–', '-'), '—', '-'), '至', '-'),
    '[[:space:]]+', '')
WHERE availability='CUSTOM';
UPDATE rider SET available_time='' WHERE availability<>'CUSTOM';

ALTER TABLE order_note
    ADD COLUMN actor_code ENUM('MERCHANT', 'RIDER_SCAN', 'RIDER_DELIVERY', 'FULFILLMENT_SERVICE') CHARACTER SET ascii COLLATE ascii_bin,
    ADD COLUMN previous_status ENUM('PENDING_PAYMENT', 'AWAITING_MERCHANT', 'PREPARING_FOOD', 'AWAITING_RIDER', 'AWAITING_PICKUP', 'DELIVERING', 'COMPLETED', 'CLOSED') CHARACTER SET ascii COLLATE ascii_bin,
    ADD COLUMN next_status ENUM('PENDING_PAYMENT', 'AWAITING_MERCHANT', 'PREPARING_FOOD', 'AWAITING_RIDER', 'AWAITING_PICKUP', 'DELIVERING', 'COMPLETED', 'CLOSED') CHARACTER SET ascii COLLATE ascii_bin;
UPDATE order_note SET actor_code = CASE CAST(actor AS BINARY)
    WHEN '商户' THEN 'MERCHANT'
    WHEN '骑手扫码' THEN 'RIDER_SCAN'
    WHEN '骑手送达' THEN 'RIDER_DELIVERY'
    WHEN '履约服务' THEN 'FULFILLMENT_SERVICE'
    END;

ALTER TABLE admin_audit_log
    ADD COLUMN before_status ENUM('PENDING_REVIEW', 'APPROVED', 'REJECTED', 'REVIEW_REJECTED', 'PENDING_PAYOUT', 'TRANSFERRING', 'AWAITING_CONFIRMATION', 'PAID', 'PAYOUT_FAILED', 'UPDATED', 'RESET', 'PREVIOUS_VERSION', 'NEW_VERSION', 'ENABLED', 'DISABLED') CHARACTER SET ascii COLLATE ascii_bin,
    ADD COLUMN after_status ENUM('PENDING_REVIEW', 'APPROVED', 'REJECTED', 'REVIEW_REJECTED', 'PENDING_PAYOUT', 'TRANSFERRING', 'AWAITING_CONFIRMATION', 'PAID', 'PAYOUT_FAILED', 'UPDATED', 'RESET', 'PREVIOUS_VERSION', 'NEW_VERSION', 'ENABLED', 'DISABLED') CHARACTER SET ascii COLLATE ascii_bin;
UPDATE admin_audit_log SET before_status = CASE CAST(before_state AS BINARY)
    WHEN '待审核' THEN 'PENDING_REVIEW'
    WHEN '已通过' THEN 'APPROVED'
    WHEN '已驳回' THEN 'REJECTED'
    WHEN '审核拒绝' THEN 'REVIEW_REJECTED'
    WHEN '待打款' THEN 'PENDING_PAYOUT'
    WHEN '打款中' THEN 'TRANSFERRING'
    WHEN '待收款确认' THEN 'AWAITING_CONFIRMATION'
    WHEN '打款成功' THEN 'PAID'
    WHEN '打款失败' THEN 'PAYOUT_FAILED'
    WHEN '已更新' THEN 'UPDATED'
    WHEN '已重置' THEN 'RESET'
    WHEN '旧版本' THEN 'PREVIOUS_VERSION'
    WHEN '新版本' THEN 'NEW_VERSION'
    WHEN '启用' THEN 'ENABLED'
    WHEN '停用' THEN 'DISABLED'
    END,
    after_status = CASE CAST(after_state AS BINARY)
    WHEN '待审核' THEN 'PENDING_REVIEW'
    WHEN '已通过' THEN 'APPROVED'
    WHEN '已驳回' THEN 'REJECTED'
    WHEN '审核拒绝' THEN 'REVIEW_REJECTED'
    WHEN '待打款' THEN 'PENDING_PAYOUT'
    WHEN '打款中' THEN 'TRANSFERRING'
    WHEN '待收款确认' THEN 'AWAITING_CONFIRMATION'
    WHEN '打款成功' THEN 'PAID'
    WHEN '打款失败' THEN 'PAYOUT_FAILED'
    WHEN '已更新' THEN 'UPDATED'
    WHEN '已重置' THEN 'RESET'
    WHEN '旧版本' THEN 'PREVIOUS_VERSION'
    WHEN '新版本' THEN 'NEW_VERSION'
    WHEN '启用' THEN 'ENABLED'
    WHEN '停用' THEN 'DISABLED'
    END;
UPDATE admin_audit_log SET before_state=NULL WHERE before_status IS NOT NULL;
UPDATE admin_audit_log SET after_state=NULL WHERE after_status IS NOT NULL;
UPDATE admin_audit_log SET after_status = IF(SUBSTRING_INDEX(after_state,'/',-1)='启用','ENABLED','DISABLED'),
    after_state=SUBSTRING_INDEX(after_state,'/',1)
WHERE action_name='admin.access' AND SUBSTRING_INDEX(after_state,'/',-1) IN ('启用','停用');


-- 旧版流水没有统一的结算状态。保留无法归类的旧说明供财务查看。
ALTER TABLE finance_ledger
    ADD COLUMN settlement_code ENUM('PENDING_PAYMENT','PENDING_SETTLEMENT','WITHDRAWABLE','SETTLED',
        'WITHDRAWN','CLOSED','SETTLEMENT_HELD','PENDING_CREDIT','LEGACY_DETAIL') CHARACTER SET ascii COLLATE ascii_bin,
    ADD COLUMN settlement_detail VARCHAR(30);
UPDATE finance_ledger SET settlement_code = CASE CAST(settlement AS BINARY)
    WHEN '待支付' THEN 'PENDING_PAYMENT'
    WHEN '待结算' THEN 'PENDING_SETTLEMENT'
    WHEN '可提现' THEN 'WITHDRAWABLE'
    WHEN '已结算' THEN 'SETTLED'
    WHEN '已提现' THEN 'WITHDRAWN'
    WHEN '已关闭' THEN 'CLOSED'
    WHEN '售后暂缓' THEN 'SETTLEMENT_HELD'
    WHEN '待入账' THEN 'PENDING_CREDIT'
    ELSE IF(CAST(settlement AS BINARY) IN ('PENDING_PAYMENT','PENDING_SETTLEMENT','WITHDRAWABLE',
        'SETTLED','WITHDRAWN','CLOSED','SETTLEMENT_HELD','PENDING_CREDIT'), settlement, 'LEGACY_DETAIL')
END;
UPDATE finance_ledger SET settlement_detail=settlement WHERE settlement_code='LEGACY_DETAIL';
ALTER TABLE finance_ledger
    DROP COLUMN settlement,
    CHANGE settlement_code settlement ENUM('PENDING_PAYMENT','PENDING_SETTLEMENT','WITHDRAWABLE','SETTLED',
        'WITHDRAWN','CLOSED','SETTLEMENT_HELD','PENDING_CREDIT','LEGACY_DETAIL') CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ADD CONSTRAINT chk_finance_ledger_settlement_code CHECK (settlement <> '');

DROP VIEW withdrawal_request_display;

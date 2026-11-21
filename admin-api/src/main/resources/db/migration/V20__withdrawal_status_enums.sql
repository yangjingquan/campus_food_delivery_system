-- 遇到未定义的历史状态时中止，避免将资金状态转换成默认值。
CREATE TEMPORARY TABLE withdrawal_status_migration_check (
    valid_status BOOLEAN NOT NULL CHECK (valid_status = TRUE)
);
INSERT INTO withdrawal_status_migration_check
SELECT NOT EXISTS (
    SELECT 1 FROM withdrawal_request WHERE CAST(status AS BINARY) NOT IN (
        '待审核', '审核拒绝', '待打款', '打款中', '待收款确认', '打款成功', '打款失败',
        'PENDING_REVIEW', 'REVIEW_REJECTED', 'PENDING_PAYOUT', 'TRANSFERRING',
        'AWAITING_CONFIRMATION', 'PAID', 'PAYOUT_FAILED')
) AND NOT EXISTS (
    SELECT 1 FROM withdrawal_intent WHERE CAST(status AS BINARY) NOT IN (
        '处理中', '已受理', '余额不足', 'PROCESSING', 'ACCEPTED', 'INSUFFICIENT_FUNDS')
) AND NOT EXISTS (
    SELECT 1 FROM withdrawal_transfer WHERE CAST(status AS BINARY) NOT IN (
        '处理中', '未知', '成功', '失败', 'PROCESSING', 'UNKNOWN', 'SUCCEEDED', 'FAILED')
);
DROP TEMPORARY TABLE withdrawal_status_migration_check;

UPDATE withdrawal_request SET status = CASE status
    WHEN '待审核' THEN 'PENDING_REVIEW'
    WHEN '审核拒绝' THEN 'REVIEW_REJECTED'
    WHEN '待打款' THEN 'PENDING_PAYOUT'
    WHEN '打款中' THEN 'TRANSFERRING'
    WHEN '待收款确认' THEN 'AWAITING_CONFIRMATION'
    WHEN '打款成功' THEN 'PAID'
    WHEN '打款失败' THEN 'PAYOUT_FAILED'
    ELSE status END
WHERE status IN ('待审核', '审核拒绝', '待打款', '打款中', '待收款确认', '打款成功', '打款失败');

UPDATE withdrawal_intent SET status = CASE status
    WHEN '处理中' THEN 'PROCESSING'
    WHEN '已受理' THEN 'ACCEPTED'
    WHEN '余额不足' THEN 'INSUFFICIENT_FUNDS'
    ELSE status END
WHERE status IN ('处理中', '已受理', '余额不足');

UPDATE withdrawal_transfer SET updated_at = updated_at, status = CASE status
    WHEN '处理中' THEN 'PROCESSING'
    WHEN '未知' THEN 'UNKNOWN'
    WHEN '成功' THEN 'SUCCEEDED'
    WHEN '失败' THEN 'FAILED'
    ELSE status END
WHERE status IN ('处理中', '未知', '成功', '失败');

ALTER TABLE withdrawal_request
    MODIFY status ENUM('PENDING_REVIEW', 'REVIEW_REJECTED', 'PENDING_PAYOUT', 'TRANSFERRING',
        'AWAITING_CONFIRMATION', 'PAID', 'PAYOUT_FAILED') CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ADD CONSTRAINT chk_withdrawal_request_status CHECK (status <> '');

ALTER TABLE withdrawal_intent
    MODIFY status ENUM('PROCESSING', 'ACCEPTED', 'INSUFFICIENT_FUNDS')
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ADD CONSTRAINT chk_withdrawal_intent_status CHECK (status <> '');

ALTER TABLE withdrawal_transfer
    MODIFY status ENUM('PROCESSING', 'UNKNOWN', 'SUCCEEDED', 'FAILED')
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ADD CONSTRAINT chk_withdrawal_transfer_status CHECK (status <> '');

CREATE INDEX idx_audit_target_action ON admin_audit_log(target_id, action_name);

-- 各端继续使用原接口的中文标签。
CREATE SQL SECURITY INVOKER VIEW withdrawal_request_display AS
SELECT id, applicant, applicant_type, amount_cents, status, created_at, reason, account_id,
    CASE status
        WHEN 'PENDING_REVIEW' THEN '待审核'
        WHEN 'REVIEW_REJECTED' THEN '审核拒绝'
        WHEN 'PENDING_PAYOUT' THEN '待打款'
        WHEN 'TRANSFERRING' THEN '打款中'
        WHEN 'AWAITING_CONFIRMATION' THEN '待收款确认'
        WHEN 'PAID' THEN '打款成功'
        WHEN 'PAYOUT_FAILED' THEN '打款失败'
    END AS status_label
FROM withdrawal_request;

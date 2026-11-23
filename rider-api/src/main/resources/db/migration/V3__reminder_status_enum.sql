CREATE TEMPORARY TABLE reminder_status_migration_check (valid_status BOOLEAN NOT NULL CHECK (valid_status = TRUE));
INSERT INTO reminder_status_migration_check
SELECT NOT EXISTS (
    SELECT 1 FROM rider_reminder_subscription
    WHERE last_status NOT IN ('发送中','已送达微信','SENDING','SENT','FAILED')
      AND last_status NOT LIKE '微信返回 %'
);
DROP TEMPORARY TABLE reminder_status_migration_check;

ALTER TABLE rider_reminder_subscription ADD COLUMN last_error_code VARCHAR(32);
UPDATE rider_reminder_subscription SET last_error_code=SUBSTRING_INDEX(last_status,' ', -1)
WHERE last_status LIKE '微信返回 %';
UPDATE rider_reminder_subscription SET last_status = CASE
    WHEN last_status='发送中' THEN 'SENDING'
    WHEN last_status='已送达微信' THEN 'SENT'
    WHEN last_status LIKE '微信返回 %' THEN 'FAILED'
    ELSE last_status END;
ALTER TABLE rider_reminder_subscription
    MODIFY last_status ENUM('SENDING', 'SENT', 'FAILED') CHARACTER SET ascii COLLATE ascii_bin,
    ADD CONSTRAINT chk_rider_reminder_status_code CHECK (last_status <> '');

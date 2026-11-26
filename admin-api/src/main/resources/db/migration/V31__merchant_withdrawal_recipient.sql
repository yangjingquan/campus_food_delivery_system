ALTER TABLE withdrawal_request
    ADD COLUMN applicant_identity_id BIGINT,
    ADD COLUMN recipient_app_id CHAR(18) CHARACTER SET ascii COLLATE ascii_bin,
    ADD COLUMN recipient_open_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin,
    ADD INDEX idx_withdraw_applicant (account_id, applicant_identity_id, status),
    ADD CONSTRAINT chk_merchant_withdrawal_recipient CHECK (
        (applicant_identity_id IS NULL AND recipient_app_id IS NULL AND recipient_open_id IS NULL)
        OR (applicant_type='MERCHANT' AND applicant_identity_id IS NOT NULL
            AND recipient_app_id IS NOT NULL AND recipient_open_id IS NOT NULL
            AND recipient_app_id<>'' AND recipient_open_id<>'')
    );

ALTER TABLE withdrawal_intent ADD COLUMN applicant_identity_id BIGINT;

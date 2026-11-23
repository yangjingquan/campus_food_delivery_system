ALTER TABLE withdrawal_transfer ADD COLUMN retry_requested BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE account_journal ADD COLUMN request_key VARCHAR(100), ADD COLUMN debt_id VARCHAR(64), ADD INDEX idx_journal_debt (debt_id);

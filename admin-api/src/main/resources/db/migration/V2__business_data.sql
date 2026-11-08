CREATE TABLE business_order (
 id VARCHAR(64) PRIMARY KEY, payment_id VARCHAR(64) NOT NULL, merchant_id VARCHAR(32) NOT NULL,
 merchant VARCHAR(120) NOT NULL, rider VARCHAR(120) NOT NULL DEFAULT '', items VARCHAR(500) NOT NULL,
 food_cents BIGINT NOT NULL, delivery_cents BIGINT NOT NULL, paid_cents BIGINT NOT NULL,
 refunded_cents BIGINT NOT NULL DEFAULT 0, food_refunded_cents BIGINT NOT NULL DEFAULT 0,
 payment_status VARCHAR(30) NOT NULL, fulfill_status VARCHAR(60) NOT NULL, refund_status VARCHAR(30) NOT NULL DEFAULT '无售后',
 settlement_status VARCHAR(30) NOT NULL DEFAULT '待结算', accepted BOOLEAN NOT NULL DEFAULT FALSE,
 pending_refund BOOLEAN NOT NULL DEFAULT FALSE, locking BOOLEAN NOT NULL DEFAULT FALSE,
 payment_count INT NOT NULL DEFAULT 0, paid_at BIGINT NOT NULL, completed_at BIGINT, closed_at BIGINT,
 last_refund_at BIGINT, payment_last_refund_at BIGINT, intervene BOOLEAN NOT NULL DEFAULT FALSE,
 intervene_reason VARCHAR(500), INDEX idx_order_payment (payment_id), INDEX idx_order_merchant (merchant_id),
 CHECK (food_cents >= 0 AND delivery_cents >= 0 AND paid_cents >= 0 AND refunded_cents >= 0 AND refunded_cents <= paid_cents)
) ENGINE=InnoDB;
CREATE TABLE order_note (id BIGINT AUTO_INCREMENT PRIMARY KEY, order_id VARCHAR(64) NOT NULL, actor VARCHAR(80) NOT NULL, note VARCHAR(500) NOT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, FOREIGN KEY(order_id) REFERENCES business_order(id)) ENGINE=InnoDB;
CREATE TABLE refund_record (
 id VARCHAR(64) PRIMARY KEY, order_id VARCHAR(64) NOT NULL, payment_id VARCHAR(64) NOT NULL,
 reason VARCHAR(500) NOT NULL, type VARCHAR(20) NOT NULL, amount_cents BIGINT NOT NULL,
 status VARCHAR(30) NOT NULL, channel_status VARCHAR(160) NOT NULL, channel_reason VARCHAR(500),
 created_at BIGINT NOT NULL, operator VARCHAR(80) NOT NULL,
 offline_channel VARCHAR(80), offline_proof VARCHAR(500), offline_operator VARCHAR(80), offline_actual_at BIGINT,
 INDEX idx_refund_payment(payment_id), FOREIGN KEY(order_id) REFERENCES business_order(id), CHECK(amount_cents >= 0)
) ENGINE=InnoDB;
CREATE TABLE withdrawal_request (id VARCHAR(64) PRIMARY KEY, applicant VARCHAR(120) NOT NULL, applicant_type VARCHAR(20) NOT NULL, amount_cents BIGINT NOT NULL, status VARCHAR(30) NOT NULL, created_at VARCHAR(32) NOT NULL, reason VARCHAR(500), CHECK(amount_cents > 0)) ENGINE=InnoDB;
CREATE TABLE finance_account (id VARCHAR(64) PRIMARY KEY, name VARCHAR(120) NOT NULL, type VARCHAR(20) NOT NULL, balance_cents BIGINT NOT NULL DEFAULT 0, status VARCHAR(30) NOT NULL, CHECK(balance_cents >= 0)) ENGINE=InnoDB;
CREATE TABLE finance_ledger (id VARCHAR(64) PRIMARY KEY, order_id VARCHAR(64), subject VARCHAR(120) NOT NULL, type VARCHAR(30) NOT NULL, food_cents BIGINT NOT NULL DEFAULT 0, delivery_cents BIGINT NOT NULL DEFAULT 0, paid_cents BIGINT NOT NULL DEFAULT 0, refund_cents BIGINT NOT NULL DEFAULT 0, settlement VARCHAR(30) NOT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP) ENGINE=InnoDB;
CREATE TABLE finance_debt (id VARCHAR(64) PRIMARY KEY, order_id VARCHAR(64) NOT NULL, merchant VARCHAR(120) NOT NULL, rider VARCHAR(120) NOT NULL, rider_due_cents BIGINT NOT NULL, merchant_balance_cents BIGINT NOT NULL, platform_advance_cents BIGINT NOT NULL, recovered_cents BIGINT NOT NULL DEFAULT 0, note VARCHAR(500) NOT NULL DEFAULT '') ENGINE=InnoDB;
CREATE TABLE operations_config (id BIGINT AUTO_INCREMENT PRIMARY KEY, fee_cents INT NOT NULL, food_rate DECIMAL(5,2) NOT NULL, delivery_rate DECIMAL(5,2) NOT NULL, regular_batch INT NOT NULL, peak_batch INT NOT NULL, service_start CHAR(5) NOT NULL, service_end CHAR(5) NOT NULL, peak_start CHAR(5) NOT NULL, peak_end CHAR(5) NOT NULL, accept_timeout INT NOT NULL, reminder_interval INT NOT NULL, operator VARCHAR(80) NOT NULL, effective_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP) ENGINE=InnoDB;
INSERT INTO operations_config(fee_cents,food_rate,delivery_rate,regular_batch,peak_batch,service_start,service_end,peak_start,peak_end,accept_timeout,reminder_interval,operator) VALUES(400,10,20,30,20,'10:30','20:30','11:30','13:30',30,15,'系统初始化');

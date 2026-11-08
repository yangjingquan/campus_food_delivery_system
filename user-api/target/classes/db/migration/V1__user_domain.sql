CREATE TABLE user_profile (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  openid VARCHAR(128) NOT NULL UNIQUE,
  display_name VARCHAR(80) NOT NULL DEFAULT '同学',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB;

CREATE TABLE user_session (
  token_hash CHAR(64) NOT NULL PRIMARY KEY,
  user_id BIGINT NOT NULL,
  expires_at TIMESTAMP NOT NULL,
  revoked BOOLEAN NOT NULL DEFAULT FALSE,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_user_session_expiry (expires_at),
  CONSTRAINT fk_user_session_profile FOREIGN KEY (user_id) REFERENCES user_profile(id)
) ENGINE=InnoDB;

CREATE TABLE user_address (
  id VARCHAR(36) NOT NULL PRIMARY KEY,
  user_id BIGINT NOT NULL,
  receiver_name VARCHAR(40) NOT NULL,
  phone VARCHAR(20) NOT NULL,
  building VARCHAR(80) NOT NULL,
  room VARCHAR(80) NOT NULL,
  is_default BOOLEAN NOT NULL DEFAULT FALSE,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_user_address_owner (user_id, is_default),
  CONSTRAINT fk_user_address_profile FOREIGN KEY (user_id) REFERENCES user_profile(id)
) ENGINE=InnoDB;

CREATE TABLE merchant_product (
  id VARCHAR(36) NOT NULL PRIMARY KEY,
  merchant_id VARCHAR(32) NOT NULL,
  name VARCHAR(120) NOT NULL,
  description VARCHAR(500) NOT NULL DEFAULT '',
  category VARCHAR(40) NOT NULL DEFAULT '餐品',
  price_cents BIGINT NOT NULL,
  stock INT NOT NULL DEFAULT 0,
  reserved INT NOT NULL DEFAULT 0,
  sold_count INT NOT NULL DEFAULT 0,
  artwork VARCHAR(24) NOT NULL DEFAULT 'rice',
  color VARCHAR(16) NOT NULL DEFAULT '#f6ecdc',
  available BOOLEAN NOT NULL DEFAULT TRUE,
  display_order INT NOT NULL DEFAULT 0,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_product_merchant (merchant_id, available, display_order),
  CHECK (price_cents >= 0 AND stock >= 0 AND reserved >= 0 AND sold_count >= 0)
) ENGINE=InnoDB;

CREATE TABLE payment_order (
  id VARCHAR(64) NOT NULL PRIMARY KEY,
  user_id BIGINT NOT NULL,
  request_key VARCHAR(80) NOT NULL,
  request_fingerprint CHAR(64) NOT NULL,
  amount_cents BIGINT NOT NULL,
  status VARCHAR(40) NOT NULL,
  payment_mode VARCHAR(20) NOT NULL,
  transaction_id VARCHAR(128),
  expires_at TIMESTAMP NOT NULL,
  paid_at BIGINT,
  refund_count INT NOT NULL DEFAULT 0,
  last_refund_at BIGINT,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_payment_user (user_id, created_at),
  INDEX idx_payment_expiry (status, expires_at),
  UNIQUE KEY uq_payment_user_request (user_id, request_key),
  CONSTRAINT fk_payment_profile FOREIGN KEY (user_id) REFERENCES user_profile(id),
  CHECK (amount_cents >= 0 AND refund_count >= 0)
) ENGINE=InnoDB;

CREATE TABLE user_order_detail (
  order_id VARCHAR(64) NOT NULL PRIMARY KEY,
  user_id BIGINT NOT NULL,
  payment_id VARCHAR(64) NOT NULL,
  address_id VARCHAR(36),
  receiver_name VARCHAR(40) NOT NULL,
  phone VARCHAR(20) NOT NULL,
  building VARCHAR(80) NOT NULL,
  room VARCHAR(80) NOT NULL,
  first_closed_at BIGINT,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_user_order_owner (user_id, created_at),
  INDEX idx_user_order_payment (payment_id),
  CONSTRAINT fk_user_order_profile FOREIGN KEY (user_id) REFERENCES user_profile(id),
  CONSTRAINT fk_user_order_payment FOREIGN KEY (payment_id) REFERENCES payment_order(id)
) ENGINE=InnoDB;

CREATE TABLE user_order_item (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  order_id VARCHAR(64) NOT NULL,
  product_id VARCHAR(36) NOT NULL,
  name_snapshot VARCHAR(120) NOT NULL,
  price_cents BIGINT NOT NULL,
  quantity INT NOT NULL,
  amount_cents BIGINT NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_user_order_items_order (order_id),
  INDEX idx_user_order_items_product (product_id),
  CHECK (price_cents >= 0 AND quantity > 0 AND amount_cents >= 0)
) ENGINE=InnoDB;

CREATE TABLE user_refund_detail (
  refund_id VARCHAR(64) NOT NULL PRIMARY KEY,
  description VARCHAR(500) NOT NULL DEFAULT '',
  wait_until BIGINT,
  original_online_failure VARCHAR(500),
  INDEX idx_user_refund_detail_id (refund_id)
) ENGINE=InnoDB;

CREATE TABLE user_after_sale_event (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  refund_id VARCHAR(64) NOT NULL,
  status VARCHAR(40) NOT NULL,
  message VARCHAR(500) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_after_sale_event (refund_id, id)
) ENGINE=InnoDB;

CREATE TABLE delivery_location (
  order_id VARCHAR(64) NOT NULL PRIMARY KEY,
  latitude DECIMAL(10,7) NOT NULL,
  longitude DECIMAL(10,7) NOT NULL,
  accuracy_m DECIMAL(8,2),
  rider_name VARCHAR(120) NOT NULL DEFAULT '',
  captured_at BIGINT NOT NULL,
  INDEX idx_delivery_location_time (captured_at),
  CHECK (latitude >= -90 AND latitude <= 90 AND longitude >= -180 AND longitude <= 180)
) ENGINE=InnoDB;

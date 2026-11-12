CREATE TABLE rider_identity (
    id VARCHAR(32) PRIMARY KEY,
    app_id VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    openid VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    UNIQUE KEY uq_rider_wechat (app_id, openid)
) ENGINE=InnoDB;

CREATE TABLE rider_session (
    token_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    rider_id VARCHAR(32) NOT NULL,
    expires_at BIGINT NOT NULL,
    INDEX idx_rider_session_expiry (expires_at),
    CONSTRAINT fk_rider_session_identity FOREIGN KEY (rider_id) REFERENCES rider_identity(id)
) ENGINE=InnoDB;

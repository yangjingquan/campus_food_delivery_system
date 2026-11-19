CREATE TABLE printing_settings (
    id TINYINT PRIMARY KEY CHECK (id = 1),
    app_id VARCHAR(128) NOT NULL DEFAULT '',
    secret_id BIGINT,
    FOREIGN KEY (secret_id) REFERENCES wechat_secret(id)
) ENGINE=InnoDB;

INSERT INTO printing_settings(id) VALUES (1);

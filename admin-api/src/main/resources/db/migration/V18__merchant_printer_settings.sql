CREATE TABLE merchant_printer_settings (
    merchant_id VARCHAR(32) PRIMARY KEY,
    sn VARCHAR(128) NOT NULL,
    pkey_secret_id BIGINT NOT NULL,
    FOREIGN KEY (merchant_id) REFERENCES merchant(id) ON DELETE CASCADE,
    FOREIGN KEY (pkey_secret_id) REFERENCES wechat_secret(id)
) ENGINE=InnoDB;

INSERT INTO admin_permission VALUES ('merchant.printer.edit', '维护商户打印机');
INSERT INTO admin_role_permission VALUES
    ('super', 'merchant.printer.edit'),
    ('operations', 'merchant.printer.edit');

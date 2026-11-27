ALTER TABLE user_order_detail
    ADD COLUMN order_remark VARCHAR(120) NOT NULL DEFAULT '',
    ADD COLUMN tableware_note VARCHAR(32) NOT NULL DEFAULT '';

CREATE TABLE user_refund_photo (
    refund_id VARCHAR(64) PRIMARY KEY,
    photo_data MEDIUMBLOB NOT NULL,
    media_type VARCHAR(16) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_user_refund_photo_record FOREIGN KEY (refund_id) REFERENCES refund_record(id)
) ENGINE=InnoDB;

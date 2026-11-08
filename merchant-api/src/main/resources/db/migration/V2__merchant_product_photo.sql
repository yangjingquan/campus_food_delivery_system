CREATE TABLE merchant_product_photo (
    product_id VARCHAR(36) PRIMARY KEY,
    image_data MEDIUMBLOB NOT NULL,
    media_type VARCHAR(20) NOT NULL,
    FOREIGN KEY (product_id) REFERENCES merchant_product(id) ON DELETE CASCADE
) ENGINE=InnoDB;

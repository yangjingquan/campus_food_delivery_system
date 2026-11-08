CREATE TABLE merchant_store_photo (
    merchant_id VARCHAR(32) PRIMARY KEY,
    image_data MEDIUMBLOB NOT NULL,
    media_type VARCHAR(20) NOT NULL,
    FOREIGN KEY (merchant_id) REFERENCES merchant(id)
) ENGINE=InnoDB;

CREATE TABLE merchant_profile_revision (
    merchant_id VARCHAR(32) PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    contact VARCHAR(80) NOT NULL,
    phone VARCHAR(32) NOT NULL,
    address VARCHAR(255) NOT NULL,
    image_data MEDIUMBLOB,
    media_type VARCHAR(20),
    status VARCHAR(30) NOT NULL,
    reason VARCHAR(500),
    FOREIGN KEY (merchant_id) REFERENCES merchant(id)
) ENGINE=InnoDB;

CREATE TABLE rider_student_card (
    rider_id VARCHAR(32) PRIMARY KEY,
    media_type VARCHAR(24) NOT NULL,
    image_data MEDIUMBLOB NOT NULL,
    CONSTRAINT fk_student_card_rider FOREIGN KEY (rider_id) REFERENCES rider(id)
) ENGINE=InnoDB;

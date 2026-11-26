ALTER TABLE rider_identity
    ADD COLUMN rider_id VARCHAR(32),
    ADD UNIQUE KEY uq_rider_identity_profile (rider_id),
    ADD CONSTRAINT fk_rider_identity_profile FOREIGN KEY (rider_id) REFERENCES rider(id);

UPDATE rider_identity i JOIN rider r ON r.id=i.id SET i.rider_id=r.id;

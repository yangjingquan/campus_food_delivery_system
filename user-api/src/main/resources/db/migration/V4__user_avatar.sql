ALTER TABLE user_profile
    ADD COLUMN avatar_data MEDIUMBLOB NULL,
    ADD COLUMN avatar_media_type VARCHAR(32) NULL;

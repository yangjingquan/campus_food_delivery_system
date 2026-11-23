ALTER TABLE rider_transfer_channel
    ADD COLUMN recipient_open_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin,
    ADD COLUMN request_payload JSON;

ALTER TABLE merchant_transfer_channel ADD COLUMN request_payload JSON;

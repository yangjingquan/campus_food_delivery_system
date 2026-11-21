ALTER TABLE rider_transfer_channel
    MODIFY channel_state ENUM('SUBMITTING', 'UNKNOWN', 'ACCEPTED', 'PROCESSING',
        'WAIT_USER_CONFIRM', 'TRANSFERING', 'SUCCESS', 'FAIL', 'CANCELING', 'CANCELLED')
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'SUBMITTING',
    ADD CONSTRAINT chk_rider_transfer_channel_status CHECK (channel_state <> '');

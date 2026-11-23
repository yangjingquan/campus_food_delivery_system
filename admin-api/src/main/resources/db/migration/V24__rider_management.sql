ALTER TABLE rider
    ADD COLUMN enabled BOOLEAN NOT NULL DEFAULT TRUE,
    MODIFY audit_status ENUM('PENDING_REVIEW', 'APPROVED', 'REJECTED', 'NOT_ENROLLED')
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL;

INSERT INTO admin_permission VALUES ('rider.manage','维护骑手资料');
INSERT INTO admin_role_permission VALUES ('super','rider.manage'),('operations','rider.manage');

INSERT INTO admin_permission (code, description) VALUES ('user.view', '查看小程序用户');

INSERT INTO admin_role_permission (role_code, permission_code) VALUES
    ('super', 'user.view'),
    ('operations', 'user.view');

CREATE TABLE admin_role (code VARCHAR(40) PRIMARY KEY, name VARCHAR(60) NOT NULL UNIQUE) ENGINE=InnoDB;
CREATE TABLE admin_permission (code VARCHAR(64) PRIMARY KEY, description VARCHAR(100) NOT NULL) ENGINE=InnoDB;
CREATE TABLE admin_role_permission (role_code VARCHAR(40) NOT NULL, permission_code VARCHAR(64) NOT NULL, PRIMARY KEY(role_code,permission_code), FOREIGN KEY(role_code) REFERENCES admin_role(code), FOREIGN KEY(permission_code) REFERENCES admin_permission(code)) ENGINE=InnoDB;
CREATE TABLE admin_user_role (user_id BIGINT NOT NULL, role_code VARCHAR(40) NOT NULL, PRIMARY KEY(user_id,role_code), FOREIGN KEY(user_id) REFERENCES admin_user(id), FOREIGN KEY(role_code) REFERENCES admin_role(code)) ENGINE=InnoDB;
INSERT INTO configuration_lock(name) VALUES ('admin');
INSERT INTO admin_role VALUES ('super','超级管理员'),('operations','运营审核'),('finance','财务'),('readonly','只读');
INSERT INTO admin_permission VALUES
 ('overview.view','查看概览'),('merchant.view','查看商户'),('merchant.audit','审核商户'),('rider.view','查看骑手'),('rider.audit','审核骑手'),('order.view','查看订单'),('order.coordinate','记录订单协调'),('refund.view','查看售后'),('refund.manage','处理退款'),('finance.view','查看财务'),('withdrawal.view','查看提现'),('withdrawal.review','审核提现'),('config.view','查看运营配置'),('config.edit','修改运营配置'),('wechat.view','查看微信配置'),('wechat.edit','修改微信配置'),('wechat.toggle','启停微信支付'),('admin.manage','管理员管理');
INSERT INTO admin_role_permission SELECT 'super',code FROM admin_permission;
INSERT INTO admin_role_permission VALUES
 ('operations','overview.view'),('operations','merchant.view'),('operations','merchant.audit'),('operations','rider.view'),('operations','rider.audit'),('operations','order.view'),('operations','order.coordinate'),('operations','refund.view'),('operations','config.view'),
 ('finance','overview.view'),('finance','merchant.view'),('finance','rider.view'),('finance','order.view'),('finance','refund.view'),('finance','refund.manage'),('finance','finance.view'),('finance','withdrawal.view'),('finance','withdrawal.review'),
 ('readonly','overview.view'),('readonly','merchant.view'),('readonly','rider.view'),('readonly','order.view'),('readonly','refund.view'),('readonly','finance.view'),('readonly','withdrawal.view'),('readonly','config.view'),('readonly','wechat.view');

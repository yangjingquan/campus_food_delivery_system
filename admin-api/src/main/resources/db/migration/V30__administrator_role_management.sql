ALTER TABLE admin_role ADD COLUMN description VARCHAR(200) NOT NULL DEFAULT '' AFTER name;

UPDATE admin_role SET description = CASE code
    WHEN 'super' THEN '系统内置角色，拥有后台全部权限'
    WHEN 'operations' THEN '用于入驻审核、商品管理和订单协同'
    WHEN 'finance' THEN '用于财务查询、退款和提现审核'
    WHEN 'readonly' THEN '用于查询后台业务数据'
    ELSE description
END;

UPDATE admin_permission SET description = '回滚微信配置（需修改微信配置权限）' WHERE code = 'wechat.toggle';
UPDATE admin_permission SET description = '处理退款（接口预留）' WHERE code = 'refund.manage';

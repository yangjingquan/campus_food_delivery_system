package com.campusfood.admin.entity;

import com.baomidou.mybatisplus.annotation.TableName;

/** 角色关联使用复合主键，只通过条件构造器进行查询和删除。 */
@TableName("admin_user_role")
public class AdminUserRole {
    private Long userId;
    private String roleCode;

    public Long getUserId() {
        return userId;
    }

    public String getRoleCode() {
        return roleCode;
    }
}

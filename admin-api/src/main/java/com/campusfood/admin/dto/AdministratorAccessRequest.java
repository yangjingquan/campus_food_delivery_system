package com.campusfood.admin.dto;

/** 更新管理员角色和启用状态的请求。 */
public record AdministratorAccessRequest(Object role, Object enabled) {
}

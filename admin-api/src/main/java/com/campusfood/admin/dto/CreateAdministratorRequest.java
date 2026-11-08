package com.campusfood.admin.dto;

/** 创建管理员时提交的账户、初始密码与角色。 */
public record CreateAdministratorRequest(String username, String password, String role) {
}

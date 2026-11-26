package com.campusfood.admin.dto;

import java.util.List;

/** 创建管理员时提交的账户、初始密码与角色。 */
public record CreateAdministratorRequest(String username, String password, List<String> roleCodes, String role) {
}

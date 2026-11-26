package com.campusfood.admin.dto;

import java.util.List;

/** 更新管理员角色和启用状态的请求。 */
public record AdministratorAccessRequest(List<String> roleCodes, String role, Object enabled) {
}

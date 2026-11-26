package com.campusfood.admin.dto;

import java.util.List;

/** 角色名称、说明及关联的权限编码。 */
public record RoleAccessRequest(String name, String description, List<String> permissions) {
}

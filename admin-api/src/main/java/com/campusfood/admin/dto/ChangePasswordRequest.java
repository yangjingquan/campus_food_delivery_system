package com.campusfood.admin.dto;

/** 管理员修改本人密码时提交的原密码和新密码。 */
public record ChangePasswordRequest(String oldPassword, String newPassword) {
}

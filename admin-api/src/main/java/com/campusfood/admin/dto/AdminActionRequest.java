package com.campusfood.admin.dto;

/** 管理员通用操作接口的请求封装，payload 的具体字段由操作类型决定。 */
public record AdminActionRequest(Object type, Object payload) {
}

package com.campusfood.admin.dto;

/** 管理端读取的商品图片内容及媒体类型。 */
public record ProductPhoto(byte[] data, String mediaType) {
}

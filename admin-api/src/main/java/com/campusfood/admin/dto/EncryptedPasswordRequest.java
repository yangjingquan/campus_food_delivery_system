package com.campusfood.admin.dto;

/** 密码操作使用一次性加密请求体。 */
public record EncryptedPasswordRequest(String challengeId, String clientPublicKey, String iv, String ciphertext) {
}

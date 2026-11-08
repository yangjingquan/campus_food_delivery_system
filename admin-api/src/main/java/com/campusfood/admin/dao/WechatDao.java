package com.campusfood.admin.dao;

import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 微信配置、加密材料和审计记录的数据访问接口。 */
@Mapper
public interface WechatDao {
    List<Map<String, Object>> listVersions();

    List<Map<String, Object>> listAudits();

    int insertSecret(@Param("purpose") String purpose, @Param("nonce") byte[] nonce,
                     @Param("ciphertext") byte[] ciphertext, @Param("fingerprint") String fingerprint);

    Long lastInsertedId();

    int insertVersion(@Param("userAppId") Object userAppId, @Param("merchantAppId") Object merchantAppId,
                      @Param("riderAppId") Object riderAppId, @Param("merchantNo") Object merchantNo,
                      @Param("serialNo") Object serialNo, @Param("publicKeyId") Object publicKeyId,
                      @Param("paymentCallback") Object paymentCallback, @Param("refundCallback") Object refundCallback,
                      @Param("transferCallback") Object transferCallback, @Param("actor") String actor,
                      @Param("userSecretId") Object userSecretId, @Param("merchantSecretId") Object merchantSecretId,
                      @Param("riderSecretId") Object riderSecretId, @Param("apiV3SecretId") Object apiV3SecretId,
                      @Param("merchantPrivateSecretId") Object merchantPrivateSecretId,
                      @Param("wechatPublicSecretId") Object wechatPublicSecretId, @Param("enabled") Object enabled);

    int insertAudit(@Param("actor") String actor, @Param("action") String action, @Param("targetId") Object targetId,
                    @Param("beforeState") String beforeState, @Param("afterState") String afterState,
                    @Param("reason") String reason);
}

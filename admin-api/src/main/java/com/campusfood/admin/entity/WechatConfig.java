package com.campusfood.admin.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** wechat_config 表的条件构造器字段映射。 */
@TableName("wechat_config")
public class WechatConfig {
    @TableId
    private Long id;
    private String userAppId;
    private String merchantAppId;
    private String riderAppId;
    private String merchantNo;
    private String serialNo;
    private String publicKeyId;
    private String paymentCallback;
    private String refundCallback;
    private String transferCallback;
    private String operator;
    private java.time.LocalDateTime createdAt;
    private Long userSecretId;
    private Long merchantSecretId;
    private Long riderSecretId;
    private Long apiV3SecretId;
    private Long merchantPrivateSecretId;
    private Long wechatPublicSecretId;
    private Boolean paymentEnabled;

    public Long getId() {
        return id;
    }

    public String getUserAppId() {
        return userAppId;
    }

    public String getMerchantAppId() {
        return merchantAppId;
    }

    public String getRiderAppId() {
        return riderAppId;
    }

    public String getMerchantNo() {
        return merchantNo;
    }

    public String getSerialNo() {
        return serialNo;
    }

    public String getPublicKeyId() {
        return publicKeyId;
    }

    public String getPaymentCallback() {
        return paymentCallback;
    }

    public String getRefundCallback() {
        return refundCallback;
    }

    public String getTransferCallback() {
        return transferCallback;
    }

    public String getOperator() {
        return operator;
    }

    public java.time.LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public Long getUserSecretId() {
        return userSecretId;
    }

    public Long getMerchantSecretId() {
        return merchantSecretId;
    }

    public Long getRiderSecretId() {
        return riderSecretId;
    }

    public Long getApiV3SecretId() {
        return apiV3SecretId;
    }

    public Long getMerchantPrivateSecretId() {
        return merchantPrivateSecretId;
    }

    public Long getWechatPublicSecretId() {
        return wechatPublicSecretId;
    }

    public Boolean getPaymentEnabled() {
        return paymentEnabled;
    }
}

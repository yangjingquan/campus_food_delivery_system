package com.campusfood.admin.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** wechat_secret 表的条件构造器字段映射。 */
@TableName("wechat_secret")
public class WechatSecret {
    @TableId
    private Long id;
    private String purpose;
    private byte[] nonce;
    private byte[] ciphertext;
    private String keyFingerprint;

    public Long getId() {
        return id;
    }

    public String getPurpose() {
        return purpose;
    }

    public byte[] getNonce() {
        return nonce;
    }

    public byte[] getCiphertext() {
        return ciphertext;
    }

    public String getKeyFingerprint() {
        return keyFingerprint;
    }
}

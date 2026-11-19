package com.campusfood.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 商户打印机配置，PKey 通过密文记录关联。 */
@TableName("merchant_printer_settings")
public class MerchantPrinterSettings {
    @TableId(type = IdType.INPUT)
    private final String merchantId;
    private final String sn;
    private final Long pkeySecretId;

    public MerchantPrinterSettings(String merchantId, String sn, Long pkeySecretId) {
        this.merchantId = merchantId;
        this.sn = sn;
        this.pkeySecretId = pkeySecretId;
    }

    public String getMerchantId() {
        return merchantId;
    }

    public String getSn() {
        return sn;
    }

    public Long getPkeySecretId() {
        return pkeySecretId;
    }
}

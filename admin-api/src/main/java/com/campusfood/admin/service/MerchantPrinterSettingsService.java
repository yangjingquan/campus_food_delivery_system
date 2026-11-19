package com.campusfood.admin.service;

import java.util.Map;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.campusfood.admin.dao.AdminDao;
import com.campusfood.admin.dao.ConfigurationLockMapper;
import com.campusfood.admin.dao.MerchantMapper;
import com.campusfood.admin.dao.MerchantPrinterSettingsMapper;
import com.campusfood.admin.dao.WechatSecretMapper;
import com.campusfood.admin.entity.Merchant;
import com.campusfood.admin.entity.MerchantPrinterSettings;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** 按商户保存设备编号和加密后的打印密钥。 */
@Service
public class MerchantPrinterSettingsService {
    private final MerchantMapper merchants;
    private final MerchantPrinterSettingsMapper settings;
    private final ConfigurationLockMapper locks;
    private final ConfigurationSecretService encryption;
    private final WechatSecretMapper secrets;
    private final AdminDao audit;

    public MerchantPrinterSettingsService(MerchantMapper merchants, MerchantPrinterSettingsMapper settings,
            ConfigurationLockMapper locks, ConfigurationSecretService encryption, WechatSecretMapper secrets,
            AdminDao audit) {
        this.merchants = merchants;
        this.settings = settings;
        this.locks = locks;
        this.encryption = encryption;
        this.secrets = secrets;
        this.audit = audit;
    }

    public Map<String, Object> current(String merchantId) {
        if (!merchants.exists(Wrappers.<Merchant>lambdaQuery().eq(Merchant::getId, merchantId))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "商户不存在");
        }
        var current = settings.selectById(merchantId);
        return current == null ? Map.of("sn", "", "pkeySet", false) : configured(current.getSn());
    }

    /** 在管理员操作事务中保存，锁定商户行以避免并发首次配置。 */
    public Map<String, Object> save(Map<?, ?> payload, String actor) {
        String merchantId = payload.get("id") instanceof String id ? id : "";
        String sn = payload.get("sn") instanceof String value ? value.strip() : "";
        if (sn.isEmpty() || sn.length() > 128) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请输入不超过 128 个字符的 SN");
        }
        Object supplied = payload.get("pkey");
        if (supplied != null && !(supplied instanceof String)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "PKey 格式无效");
        }
        String pkey = supplied instanceof String value ? value : "";
        if (pkey.length() > 512 || (!pkey.isEmpty() && pkey.isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请输入有效的 PKey，最多 512 个字符");
        }

        String lockedId = merchants.lockStore(merchantId);
        if (lockedId == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "商户不存在");
        // 先锁定共享密钥配置，再读取设备行，串行化密文写入和首次配置。
        locks.lock("wechat");
        var current = settings.selectOne(Wrappers.<MerchantPrinterSettings>lambdaQuery()
                .eq(MerchantPrinterSettings::getMerchantId, lockedId).last("FOR UPDATE"));
        if (pkey.isEmpty()) {
            if (current == null || !sn.equals(current.getSn())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "首次配置或更换 SN 时，请同时填写 PKey");
            }
            return configured(sn);
        }

        Long secretId = encryption.encrypt("merchant_printer_pkey", pkey);
        if (current == null) {
            settings.insert(new MerchantPrinterSettings(lockedId, sn, secretId));
        } else {
            settings.update(null, Wrappers.<MerchantPrinterSettings>lambdaUpdate()
                    .eq(MerchantPrinterSettings::getMerchantId, lockedId)
                    .set(MerchantPrinterSettings::getSn, sn)
                    .set(MerchantPrinterSettings::getPkeySecretId, secretId));
            secrets.deleteById(current.getPkeySecretId());
        }
        audit.insertSimpleAudit(actor, "merchant.save-printer", lockedId, "已更新");
        return configured(sn);
    }

    private Map<String, Object> configured(String sn) {
        return Map.of("sn", sn, "pkeySet", true);
    }
}

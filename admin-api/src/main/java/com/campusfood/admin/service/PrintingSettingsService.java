package com.campusfood.admin.service;

import com.campusfood.domain.BusinessStates.AdminAudit;

import java.util.Map;

import com.campusfood.admin.dao.AdminDao;
import com.campusfood.admin.dao.ConfigurationLockMapper;
import com.campusfood.admin.dao.PrintingSettingsDao;
import com.campusfood.admin.dao.WechatSecretMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PrintingSettingsService {
    private final PrintingSettingsDao settings;
    private final ConfigurationSecretService encryption;
    private final ConfigurationLockMapper locks;
    private final WechatSecretMapper secrets;
    private final AdminDao audit;

    public PrintingSettingsService(PrintingSettingsDao settings, ConfigurationSecretService encryption,
            ConfigurationLockMapper locks, WechatSecretMapper secrets, AdminDao audit) {
        this.settings = settings;
        this.encryption = encryption;
        this.locks = locks;
        this.secrets = secrets;
        this.audit = audit;
    }

    public Map<String, Object> current() {
        var current = settings.current();
        return Map.of("appId", current.appId(), "appSecretSet", current.secretId() != null);
    }

    /** 由管理员操作事务调用，凭据和审计记录一起提交。 */
    public Map<String, Object> save(Map<?, ?> payload, String actor) {
        String appId = payload.get("appId") instanceof String value ? value.strip() : "";
        if (appId.isEmpty() || appId.length() > 128)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请输入不超过 128 个字符的打印机 AppID");
        Object supplied = payload.get("appSecret");
        if (supplied != null && !(supplied instanceof String))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "打印机 AppSecret 格式无效");
        String secret = supplied instanceof String value ? value : "";
        if (secret.length() > 512 || (!secret.isEmpty() && secret.isBlank()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请输入不超过 512 个字符的打印机 AppSecret");

        var current = settings.lockCurrent();
        if (secret.isEmpty()) {
            if (current.secretId() == null || !appId.equals(current.appId()))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "首次配置或更换 AppID 时，请同时填写 AppSecret");
            return current();
        }
        // 两类配置共用密文表，沿用微信配置锁来串行化加密及密钥指纹检查。
        locks.lock("wechat");
        Long secretId = encryption.encrypt("printer_appsecret", secret);
        settings.save(appId, secretId);
        if (current.secretId() != null) secrets.deleteById(current.secretId());
        audit.insertSimpleAudit(actor, "config.save-printing", "printing", AdminAudit.UPDATED.name());
        return Map.of("appId", appId, "appSecretSet", true);
    }
}

package com.campusfood.admin.service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.*;
import java.security.spec.*;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import com.campusfood.admin.dao.WechatDao;
import com.campusfood.admin.dao.ConfigurationLockMapper;
import com.campusfood.admin.dao.WechatConfigMapper;
import com.campusfood.admin.dao.WechatSecretMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** 管理微信配置版本、敏感材料及操作审计。 */
@Service
public class WechatService {
    private final WechatDao db;
    private final ConfigurationLockMapper configurationLocks;
    private final WechatConfigMapper configurations;
    private final WechatSecretMapper secrets;
    private final SecureRandom random = new SecureRandom();
    @Value("${app.wechat-master-key-file:}")
    private String keyFile;
    @Value("${USER_PAYMENT_MODE:disabled}")
    private String paymentMode;

    public WechatService(WechatDao db, WechatSecretMapper secrets, ConfigurationLockMapper configurationLocks,
                         WechatConfigMapper configurations) {
        this.db = db;
        this.configurationLocks = configurationLocks;
        this.configurations = configurations;
        this.secrets = secrets;
    }

    private static String value(Map<?, ?> p, String key) {
        Object v = p.get(key);
        return v == null ? "" : v.toString().trim();
    }

    /** 只返回配置及敏感材料的存在状态，不返回材料明文。 */
    public Map<String, Object> workspace() {
        var all = db.listVersions();
        var audit = db.listAudits();
        var current = new LinkedHashMap<>(all.get(0));
        current.put("enabled", "wechat".equals(paymentMode));
        return Map.of("config", current, "history", all.subList(1, all.size()), "audit", audit);
    }

    private Long encrypt(String purpose, String content) {
        if (content.isEmpty()) return null;
        if (content.length() > 16384) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "材料长度超过限制");
        try {
            if (keyFile.isBlank()) throw new IllegalStateException("missing key");
            byte[] key =
                    Base64.getDecoder().decode(Files.readString(Path.of(keyFile), StandardCharsets.US_ASCII).trim());
            if (key.length != 32) throw new IllegalStateException("invalid key length");
            String fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key));
            // 历史密文先尝试解密，再补记密钥指纹，避免误用新密钥。
            var untagged = secrets.lockUntagged();
            for (var old : untagged) {
                try {
                    Cipher check = Cipher.getInstance("AES/GCM/NoPadding");
                    check.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                            new GCMParameterSpec(128, (byte[]) old.get("nonce")));
                    check.updateAAD(old.get("purpose").toString().getBytes(StandardCharsets.UTF_8));
                    byte[] plain = check.doFinal((byte[]) old.get("ciphertext"));
                    Arrays.fill(plain, (byte) 0);
                } catch (Exception ex) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "加密主密钥与已有材料不匹配，请恢复原密钥");
                }
                secrets.tagKey(fingerprint, old.get("id"));
            }
            Integer mismatched = secrets.countMismatched(fingerprint);
            if (mismatched != null && mismatched > 0)
                throw new ResponseStatusException(HttpStatus.CONFLICT, "加密主密钥与已有材料不匹配，请恢复原密钥");
            byte[] nonce = new byte[12];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            // 将用途绑定到密文，防止不同用途的敏感材料被互换。
            cipher.updateAAD(purpose.getBytes(StandardCharsets.UTF_8));
            byte[] encrypted = cipher.doFinal(content.getBytes(StandardCharsets.UTF_8));
            Arrays.fill(key, (byte) 0);
            db.insertSecret(purpose, nonce, encrypted, fingerprint);
            return db.lastInsertedId();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "密钥加密服务不可用，配置未保存");
        }
    }

    private static void validateSecret(String purpose, String text) {
        if (text.isEmpty()) return;
        if (purpose.equals("api_v3") && text.getBytes(StandardCharsets.UTF_8).length != 32)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "API v3 密钥须为 32 字节");
        if (purpose.endsWith("_appsecret") && (text.length() < 16 || text.length() > 256))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "AppSecret 长度无效");
        if (purpose.equals("merchant_private") || purpose.equals("wechat_public")) {
            String label = purpose.equals("merchant_private") ? "PRIVATE KEY" : "PUBLIC KEY";
            try {
                if (!text.startsWith("-----BEGIN " + label + "-----") || !text.endsWith("-----END " + label + "-----"))
                    throw new IllegalArgumentException();
                String raw = text.replace("-----BEGIN " + label + "-----", "").replace("-----END " + label + "-----",
                        "").replaceAll("\\s", "");
                byte[] bytes = Base64.getDecoder().decode(raw);
                if (purpose.equals("merchant_private"))
                    KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(bytes));
                else KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(bytes));
            } catch (Exception e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        purpose.equals("merchant_private") ? "商户私钥须为有效的 PKCS#8 RSA 私钥 PEM" :
                                "微信支付公钥须为有效的 RSA 公钥 PEM");
            }
        }
    }

    private static void callback(String value) {
        if (value.isEmpty()) return;
        try {
            var u = java.net.URI.create(value);
            if (!"https".equalsIgnoreCase(u.getScheme()) || u.getHost() == null || u.getUserInfo() != null ||
                    u.getFragment() != null || u.getQuery() != null || u.getPath() == null || u.getPath().isEmpty() ||
                    u.getPath().equals("/")) throw new IllegalArgumentException();
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "回调须为完整 HTTPS 地址，不能包含查询参数或片段");
        }
    }

    @Transactional
    public Map<String, Object> action(String type, Map<?, ?> p, String actor) {
        // 串行化版本变更，并检查提交版本是否仍为当前版本。
        configurationLocks.lock("wechat");
        Map<String, Object> current = configurations.lockCurrentVersion();
        if (!String.valueOf(current.get("id")).equals(value(p, "version")))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "配置已变化，请刷新后重试");
        String previousVersion = String.valueOf(current.get("id")), reason = "";
        if (type.equals("wechat.save-app")) {
            String appType = value(p, "appType");
            if (!List.of("user", "merchant", "rider").contains(appType))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "小程序类型无效");
            String appId = value(p, "appId"), secret = value(p, "appSecret");
            if (!appId.isEmpty() && !appId.matches("wx[0-9a-fA-F]{16}"))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "AppID 格式无效");
            validateSecret(appType + "_appsecret", secret);
            current.put(appType + "_app_id", appId);
            if (!secret.isEmpty()) current.put(appType + "_secret_id", encrypt(appType + "_appsecret", secret));
            reason = appType + " 端 AppID；AppSecret：" + (secret.isEmpty() ? "保留" : "已替换");
        } else if (type.equals("wechat.save-payment")) {
            String[][] fields =
                    {{"merchantNo", "merchant_no"}, {"serialNo", "serial_no"}, {"publicKeyId", "public_key_id"}, {"paymentCallback", "payment_callback"}, {"refundCallback", "refund_callback"}, {"transferCallback", "transfer_callback"}};
            for (String[] field : fields) {
                String v = value(p, field[0]);
                if (v.length() > (field[0].endsWith("Callback") ? 512 : 128))
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "配置字段过长");
                if (field[0].endsWith("Callback")) callback(v);
                current.put(field[1], v);
            }
            String merchant = value(p, "merchantNo");
            if (!merchant.isEmpty() && !merchant.matches("[0-9]{6,32}"))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "商户号须为数字");
            String[][] secrets =
                    {{"apiV3Key", "api_v3", "api_v3_secret_id"}, {"merchantPrivateKey", "merchant_private", "merchant_private_secret_id"}, {"wechatPublicKey", "wechat_public", "wechat_public_secret_id"}};
            for (String[] field : secrets) {
                String v = value(p, field[0]);
                validateSecret(field[1], v);
                if (!v.isEmpty()) current.put(field[2], encrypt(field[1], v));
            }
            reason = "支付标识及回调；API v3 密钥：" + (value(p, "apiV3Key").isEmpty() ? "保留" : "已替换") +
                    "；商户私钥：" + (value(p, "merchantPrivateKey").isEmpty() ? "保留" : "已替换") + "；微信公钥：" +
                    (value(p, "wechatPublicKey").isEmpty() ? "保留" : "已替换");
        } else if (type.equals("wechat.rollback")) {
            // 回滚复制历史配置形成新版本，不覆盖原有审计记录。
            reason = value(p, "reason");
            String target = value(p, "targetVersion");
            if (reason.isEmpty() || reason.length() > 300 || reason.contains("PRIVATE KEY") ||
                    reason.contains("PUBLIC KEY") || reason.matches(".*[A-Za-z0-9+/=]{28,}.*"))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请填写不含敏感材料的回滚原因");
            var matches = configurations.findVersion(target);
            if (matches.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "目标版本不存在");
            current = matches.get(0);
            reason = "目标版本 " + target + "；" + reason;
        } else throw new ResponseStatusException(HttpStatus.NOT_FOUND, "未知微信配置操作");
        Set<String> ids = new HashSet<>();
        for (String key : List.of("user_app_id", "merchant_app_id", "rider_app_id")) {
            String id = Objects.toString(current.get(key), "");
            if (!id.isEmpty() && !ids.add(id))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "三端 AppID 不可重复");
        }
        db.insertVersion(current.get("user_app_id"), current.get("merchant_app_id"), current.get("rider_app_id"),
                current.get("merchant_no"), current.get("serial_no"), current.get("public_key_id"),
                current.get("payment_callback"), current.get("refund_callback"), current.get("transfer_callback"),
                actor, current.get("user_secret_id"), current.get("merchant_secret_id"), current.get("rider_secret_id"),
                current.get("api_v3_secret_id"), current.get("merchant_private_secret_id"),
                current.get("wechat_public_secret_id"));
        db.insertAudit(actor, type, previousVersion, "旧版本", "新版本",
                reason.isEmpty() ? "仅记录字段变更，不含材料内容" : reason);
        return Map.of("ok", true);
    }
}

package com.campusfood.user;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Reads the current user mini-program configuration saved by the admin service. */
@Service
public class WechatConfigurationService {
    static final String CURRENT_CONFIG = "SELECT c.user_app_id,s.purpose,s.nonce,s.ciphertext,s.key_fingerprint " +
        "FROM wechat_config c LEFT JOIN wechat_secret s ON s.id=c.user_secret_id ORDER BY c.id DESC LIMIT 1";
    static final String CURRENT_APP_ID = "SELECT user_app_id FROM wechat_config ORDER BY id DESC LIMIT 1";

    private final JdbcTemplate db;
    private final String source;
    private final String fallbackAppId;
    private final String fallbackSecret;
    private final String keyFile;

    public WechatConfigurationService(JdbcTemplate db,
            @Value("${app.wechat.configuration-source:environment}") String source,
            @Value("${app.wechat.app-id:}") String fallbackAppId,
            @Value("${app.wechat.app-secret:}") String fallbackSecret,
            @Value("${app.wechat-master-key-file:}") String keyFile) {
        if (!"database".equals(source) && !"environment".equals(source)) {
            throw new IllegalArgumentException("Invalid WeChat configuration source");
        }
        this.db = db;
        this.source = source;
        this.fallbackAppId = fallbackAppId;
        this.fallbackSecret = fallbackSecret;
        this.keyFile = keyFile;
    }

    public record Credentials(String appId, String appSecret) {
        @Override public String toString() { return "Credentials[appId=" + appId + ", appSecret=REDACTED]"; }
    }

    public String currentAppId() {
        if (!"database".equals(source)) return fallbackAppId;
        List<Map<String, Object>> rows = db.queryForList(CURRENT_APP_ID);
        return rows.isEmpty() ? fallbackAppId : appId(rows.get(0));
    }

    public Credentials credentials() {
        if (!"database".equals(source)) return checked(fallbackAppId, fallbackSecret);
        byte[] key = null;
        byte[] plain = null;
        try {
            List<Map<String, Object>> rows = db.queryForList(CURRENT_CONFIG);
            if (rows.isEmpty()) throw unavailable();
            Map<String, Object> row = rows.get(0);
            if (!"user_appsecret".equals(row.get("purpose")) || !(row.get("nonce") instanceof byte[] nonce)
                    || !(row.get("ciphertext") instanceof byte[] ciphertext) || keyFile.isBlank()) throw unavailable();
            key = Base64.getDecoder().decode(Files.readString(Path.of(keyFile), StandardCharsets.US_ASCII).trim());
            if (key.length != 32 || nonce.length != 12) throw unavailable();
            String fingerprint = Objects.toString(row.get("key_fingerprint"), "");
            String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key));
            if (!fingerprint.isEmpty() && !MessageDigest.isEqual(fingerprint.getBytes(StandardCharsets.US_ASCII),
                    actual.getBytes(StandardCharsets.US_ASCII))) throw unavailable();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD("user_appsecret".getBytes(StandardCharsets.UTF_8));
            plain = cipher.doFinal(ciphertext);
            return checked(appId(row), new String(plain, StandardCharsets.UTF_8));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw unavailable();
        } finally {
            if (key != null) Arrays.fill(key, (byte) 0);
            if (plain != null) Arrays.fill(plain, (byte) 0);
        }
    }

    private String appId(Map<String, Object> row) {
        String configured = Objects.toString(row.get("user_app_id"), "").trim();
        return configured.isEmpty() ? fallbackAppId : configured;
    }

    private static Credentials checked(String appId, String secret) {
        if (appId == null || appId.isBlank() || secret == null || secret.isBlank()) throw unavailable();
        return new Credentials(appId, secret);
    }

    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "微信登录尚未配置有效的 AppID 和 AppSecret");
    }
}

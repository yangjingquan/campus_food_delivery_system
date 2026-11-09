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
        List<Map<String, Object>> rows = db.queryForList(CURRENT_CONFIG);
        if (rows.isEmpty()) throw unavailable();
        Map<String, Object> row = rows.get(0);
        return checked(appId(row), decrypt(row, "user_appsecret"));
    }

    record PaymentCredentials(long version, String appId, String merchant, String serial, String publicKeyId,
            String callback, String apiKey, String merchantKey, String publicKey) {
        @Override public String toString() { return "PaymentCredentials[version=" + version + "]"; }
    }

    Long currentPaymentVersion() {
        if (!"database".equals(source)) return null;
        return db.query("""
                SELECT id, user_app_id, merchant_no, serial_no, public_key_id, payment_callback,
                    api_v3_secret_id, merchant_private_secret_id, wechat_public_secret_id
                FROM wechat_config ORDER BY id DESC LIMIT 1
                """, rs -> {
            if (!rs.next()) return null;
            for (String column : List.of("user_app_id", "merchant_no", "serial_no", "public_key_id", "payment_callback")) {
                if (rs.getString(column).isBlank()) return null;
            }
            for (String column : List.of("api_v3_secret_id", "merchant_private_secret_id", "wechat_public_secret_id")) {
                if (rs.getObject(column) == null) return null;
            }
            return rs.getLong("id");
        });
    }

    PaymentCredentials payment(long version) {
        Map<String, Object> row = db.queryForMap("SELECT * FROM wechat_config WHERE id=?", version);
        return new PaymentCredentials(version, appId(row), required(row, "merchant_no"), required(row, "serial_no"),
            required(row, "public_key_id"), required(row, "payment_callback"),
            paymentSecret(row, "api_v3_secret_id", "api_v3"),
            paymentSecret(row, "merchant_private_secret_id", "merchant_private"),
            paymentSecret(row, "wechat_public_secret_id", "wechat_public"));
    }

    private String paymentSecret(Map<String, Object> config, String field, String purpose) {
        List<Map<String, Object>> rows = db.queryForList(
            "SELECT purpose,nonce,ciphertext,key_fingerprint FROM wechat_secret WHERE id=?", config.get(field));
        if (rows.isEmpty()) throw paymentUnavailable();
        return decrypt(rows.get(0), purpose);
    }

    private static String required(Map<String, Object> row, String field) {
        String value = Objects.toString(row.get(field), "").trim();
        if (value.isEmpty()) throw paymentUnavailable();
        return value;
    }

    private String decrypt(Map<String, Object> row, String purpose) {
        byte[] key = null;
        byte[] plain = null;
        try {
            if (!purpose.equals(row.get("purpose")) || !(row.get("nonce") instanceof byte[] nonce)
                    || !(row.get("ciphertext") instanceof byte[] ciphertext) || keyFile.isBlank()) {
                throw new IllegalArgumentException("微信密钥材料不完整");
            }
            key = Base64.getDecoder().decode(Files.readString(Path.of(keyFile), StandardCharsets.US_ASCII).trim());
            if (key.length != 32 || nonce.length != 12) throw new IllegalArgumentException("微信密钥格式错误");
            String fingerprint = Objects.toString(row.get("key_fingerprint"), "");
            String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key));
            if (!fingerprint.isEmpty() && !MessageDigest.isEqual(fingerprint.getBytes(StandardCharsets.US_ASCII),
                    actual.getBytes(StandardCharsets.US_ASCII))) throw new IllegalArgumentException("微信主密钥不匹配");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(purpose.getBytes(StandardCharsets.UTF_8));
            plain = cipher.doFinal(ciphertext);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception error) {
            throw "user_appsecret".equals(purpose) ? unavailable() : paymentUnavailable();
        } finally {
            if (key != null) Arrays.fill(key, (byte) 0);
            if (plain != null) Arrays.fill(plain, (byte) 0);
        }
    }

    static ResponseStatusException paymentUnavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "微信支付暂不可用，请稍后重试");
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

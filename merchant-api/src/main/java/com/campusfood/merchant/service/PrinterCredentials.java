package com.campusfood.merchant.service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
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

/** 打印平台凭据和商户设备密钥均复用管理后台的加密配置。 */
@Service
public class PrinterCredentials {
    public record Credentials(String appId, String appSecret, String sn, String pkey, String name) {
        @Override
        public String toString() {
            return "PrinterCredentials[redacted]";
        }
    }

    private final JdbcTemplate db;
    private final String keyFile;

    public PrinterCredentials(JdbcTemplate db, @Value("${app.wechat-master-key-file}") String keyFile) {
        this.db = db;
        this.keyFile = keyFile;
    }

    public Map<String, Object> status(String merchant) {
        var rows = db.queryForList("""
            SELECT p.sn,p.pkey_secret_id,c.app_id,c.secret_id
            FROM merchant m LEFT JOIN merchant_printer_settings p ON p.merchant_id=m.id
            LEFT JOIN printing_settings c ON c.id=1 WHERE m.id=?
            """, merchant);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "商户不存在");
        var row = rows.getFirst();
        boolean configured = row.get("pkey_secret_id") != null && row.get("secret_id") != null
                && !Objects.toString(row.get("sn"), "").isBlank()
                && !Objects.toString(row.get("app_id"), "").isBlank();
        return Map.of("configured", configured, "sn", Objects.toString(row.get("sn"), ""),
                "message", configured ? "已配置商鹏云打印，接单后自动提交小票" : "请联系管理员配置打印平台及本店设备");
    }

    public Credentials load(String merchant) {
        var rows = db.queryForList("""
            SELECT m.name,p.sn,c.app_id,
                a.purpose AS appPurpose,a.nonce AS appNonce,a.ciphertext AS appCiphertext,a.key_fingerprint AS appFingerprint,
                s.purpose AS devicePurpose,s.nonce AS deviceNonce,s.ciphertext AS deviceCiphertext,s.key_fingerprint AS deviceFingerprint
            FROM merchant m JOIN merchant_printer_settings p ON p.merchant_id=m.id
            JOIN printing_settings c ON c.id=1
            JOIN wechat_secret a ON a.id=c.secret_id
            JOIN wechat_secret s ON s.id=p.pkey_secret_id WHERE m.id=?
            """, merchant);
        if (rows.isEmpty()) throw unavailable();
        var row = rows.getFirst();
        byte[] key = null;
        try {
            key = Base64.getDecoder().decode(Files.readString(Path.of(keyFile)).trim());
            if (key.length != 32) throw new IllegalStateException();
            String fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key));
            return new Credentials(row.get("app_id").toString(),
                    decrypt(row, "app", "printer_appsecret", key, fingerprint), row.get("sn").toString(),
                    decrypt(row, "device", "merchant_printer_pkey", key, fingerprint), row.get("name").toString());
        } catch (Exception ignored) {
            throw unavailable();
        } finally {
            if (key != null) Arrays.fill(key, (byte) 0);
        }
    }

    private String decrypt(Map<String, Object> row, String prefix, String purpose, byte[] key, String fingerprint)
            throws Exception {
        String expected = Objects.toString(row.get(prefix + "Fingerprint"), "");
        if (!purpose.equals(row.get(prefix + "Purpose")) || (!expected.isEmpty() && !expected.equals(fingerprint))) {
            throw new IllegalStateException();
        }
        byte[] nonce = (byte[]) row.get(prefix + "Nonce");
        if (nonce.length != 12) throw new IllegalStateException();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(purpose.getBytes(StandardCharsets.UTF_8));
        byte[] clear = cipher.doFinal((byte[]) row.get(prefix + "Ciphertext"));
        try {
            String value = new String(clear, StandardCharsets.UTF_8);
            if (value.isBlank()) throw new IllegalStateException();
            return value;
        } finally {
            Arrays.fill(clear, (byte) 0);
        }
    }

    private ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "管理员后台的打印平台或本店设备配置不可用");
    }
}

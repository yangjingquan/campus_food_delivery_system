package com.campusfood.admin.service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import com.campusfood.admin.dao.WechatDao;
import com.campusfood.admin.dao.WechatSecretMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** 复用已有主密钥加密运营凭据，并按用途隔离密文。 */
@Service
public class ConfigurationSecretService {
    private final WechatDao db;
    private final WechatSecretMapper secrets;
    private final SecureRandom random = new SecureRandom();

    @Value("${app.wechat-master-key-file:}")
    private String keyFile;

    public ConfigurationSecretService(WechatDao db, WechatSecretMapper secrets) {
        this.db = db;
        this.secrets = secrets;
    }

    Long encrypt(String purpose, String content) {
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
}

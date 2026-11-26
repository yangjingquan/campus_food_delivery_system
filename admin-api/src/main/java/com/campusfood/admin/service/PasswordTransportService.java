package com.campusfood.admin.service;

import com.campusfood.admin.dto.EncryptedPasswordRequest;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.RedisStringCommands.SetOption;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.types.Expiration;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/** 使用临时 P-256 密钥、HKDF-SHA256 和 AES-GCM 保护管理员密码请求。 */
@Service
public class PasswordTransportService {
    private static final Duration LIFETIME = Duration.ofMinutes(2);
    private static final int MAX_CIPHERTEXT_LENGTH = 8192;
    private static final String CONTEXT = "campus-admin-password-v1:";
    private static final Base64.Encoder BASE64 = Base64.getEncoder();
    private static final Base64.Decoder DECODER = Base64.getDecoder();

    private final StringRedisTemplate redis;
    private final JsonMapper json;
    private final String prefix;

    public PasswordTransportService(StringRedisTemplate redis, JsonMapper json,
                                    @Value("${app.redis-key-prefix:}") String prefix) {
        this.redis = redis;
        this.json = json;
        this.prefix = prefix + "admin:password-challenge:";
    }

    public record Challenge(String challengeId, String publicKey) {
    }

    public Challenge challenge() {
        try {
            var generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            var keys = generator.generateKeyPair();
            String id = UUID.randomUUID().toString();
            redis.opsForValue().set(prefix + id, BASE64.encodeToString(keys.getPrivate().getEncoded()), LIFETIME);
            return new Challenge(id, BASE64.encodeToString(keys.getPublic().getEncoded()));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("无法生成密码加密密钥", ex);
        }
    }

    public <T> T decrypt(EncryptedPasswordRequest body, String path, Class<T> type) {
        if (body == null || body.challengeId() == null
                || !body.challengeId().matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")
                || body.clientPublicKey() == null || body.clientPublicKey().length() > 128
                || body.iv() == null || body.iv().length() != 16
                || body.ciphertext() == null || body.ciphertext().length() > MAX_CIPHERTEXT_LENGTH) {
            throw invalidRequest();
        }
        byte[] key = utf8(prefix + body.challengeId());
        // SET XX GET 原子读取并替换密钥，确保跨实例最多消费一次，且兼容生产 Redis 的 SET 权限。
        byte[] privateKey = redis.execute((RedisCallback<byte[]>) connection -> connection.stringCommands()
                .setGet(key, new byte[0], Expiration.keepTtl(), SetOption.ifPresent()));
        if (privateKey == null || privateKey.length == 0) throw invalidRequest();
        try {
            var keys = KeyFactory.getInstance("EC");
            var agreement = KeyAgreement.getInstance("ECDH");
            agreement.init(keys.generatePrivate(new PKCS8EncodedKeySpec(DECODER.decode(privateKey))));
            agreement.doPhase(keys.generatePublic(new X509EncodedKeySpec(DECODER.decode(body.clientPublicKey()))), true);
            var aesKey = deriveKey(agreement.generateSecret(), body.challengeId(), path);
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, aesKey, new GCMParameterSpec(128, DECODER.decode(body.iv())));
            byte[] plaintext = cipher.doFinal(DECODER.decode(body.ciphertext()));
            try {
                T result = json.readValue(plaintext, type);
                if (result == null) throw invalidRequest();
                return result;
            } finally {
                Arrays.fill(plaintext, (byte) 0);
            }
        } catch (GeneralSecurityException | IllegalArgumentException | JacksonException ex) {
            throw invalidRequest();
        } finally {
            Arrays.fill(privateKey, (byte) 0);
        }
    }

    private static SecretKeySpec deriveKey(byte[] secret, String challengeId, String path)
            throws GeneralSecurityException {
        byte[] extracted = null;
        byte[] aesKey = null;
        try {
            // HKDF-SHA256 的扩展只需一个 32 字节输出块。
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(utf8(challengeId), "HmacSHA256"));
            extracted = mac.doFinal(secret);
            mac.init(new SecretKeySpec(extracted, "HmacSHA256"));
            mac.update(utf8(CONTEXT + path));
            aesKey = mac.doFinal(new byte[]{1});
            return new SecretKeySpec(aesKey, "AES");
        } finally {
            Arrays.fill(secret, (byte) 0);
            if (extracted != null) Arrays.fill(extracted, (byte) 0);
            if (aesKey != null) Arrays.fill(aesKey, (byte) 0);
        }
    }

    private static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static ResponseStatusException invalidRequest() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "密码加密请求无效或已过期，请重试。");
    }
}

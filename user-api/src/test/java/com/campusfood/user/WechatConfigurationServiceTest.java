package com.campusfood.user;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

class WechatConfigurationServiceTest {
    @TempDir Path temp;

    @Test
    void readsAndDecryptsTheLatestAdminConfigurationOnEveryLogin() throws Exception {
        JdbcTemplate db = mock(JdbcTemplate.class);
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) key[i] = (byte) i;
        Path keyFile = temp.resolve("master.key");
        Files.writeString(keyFile, Base64.getEncoder().encodeToString(key));
        var first = encrypted(key, "wx1111111111111111", "first-user-secret");
        var second = encrypted(key, "wx2222222222222222", "second-user-secret");
        when(db.queryForList(WechatConfigurationService.CURRENT_CONFIG))
                .thenReturn(List.of(first), List.of(second));
        when(db.queryForList(WechatConfigurationService.CURRENT_APP_ID))
                .thenReturn(List.of(Map.of("user_app_id", "wx2222222222222222")));
        var service = new WechatConfigurationService(db, "database", "fallback", "", keyFile.toString());

        var before = service.credentials();
        var after = service.credentials();
        assertEquals("wx1111111111111111", before.appId());
        assertEquals("first-user-secret", before.appSecret());
        assertEquals("wx2222222222222222", after.appId());
        assertEquals("second-user-secret", after.appSecret());
        assertEquals("wx2222222222222222", service.currentAppId());
        assertFalse(after.toString().contains("second-user-secret"));
    }

    @Test
    void rejectsMissingSecretWrongKeyAndAlteredCiphertext() throws Exception {
        JdbcTemplate db = mock(JdbcTemplate.class);
        byte[] key = new byte[32];
        Path keyFile = temp.resolve("master.key");
        Files.writeString(keyFile, Base64.getEncoder().encodeToString(key));
        var service = new WechatConfigurationService(db, "database", "wx1111111111111111", "", keyFile.toString());
        when(db.queryForList(WechatConfigurationService.CURRENT_CONFIG)).thenReturn(List.of(Map.of("user_app_id", "")));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, assertThrows(ResponseStatusException.class, service::credentials).getStatusCode());

        byte[] differentKey = new byte[32];
        differentKey[0] = 1;
        var encryptedWithDifferentKey = encrypted(differentKey, "wx1111111111111111", "secret-value");
        when(db.queryForList(WechatConfigurationService.CURRENT_CONFIG)).thenReturn(List.of(encryptedWithDifferentKey));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, assertThrows(ResponseStatusException.class, service::credentials).getStatusCode());

        var altered = new java.util.HashMap<>(encrypted(key, "wx1111111111111111", "secret-value"));
        ((byte[]) altered.get("ciphertext"))[0] ^= 1;
        when(db.queryForList(WechatConfigurationService.CURRENT_CONFIG)).thenReturn(List.of(altered));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, assertThrows(ResponseStatusException.class, service::credentials).getStatusCode());
    }

    @Test
    void localEnvironmentModeDoesNotReadAdminDatabase() {
        JdbcTemplate db = mock(JdbcTemplate.class);
        var service = new WechatConfigurationService(db, "environment", "wx1111111111111111", "local-secret", "");
        assertEquals("local-secret", service.credentials().appSecret());
        assertEquals("wx1111111111111111", service.currentAppId());
        verifyNoInteractions(db);
    }

    private static Map<String, Object> encrypted(byte[] key, String appId, String secret) throws Exception {
        byte[] nonce = new byte[12];
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD("user_appsecret".getBytes(StandardCharsets.UTF_8));
        return Map.of("user_app_id", appId, "purpose", "user_appsecret", "nonce", nonce,
                "ciphertext", cipher.doFinal(secret.getBytes(StandardCharsets.UTF_8)),
                "key_fingerprint", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key)));
    }
}

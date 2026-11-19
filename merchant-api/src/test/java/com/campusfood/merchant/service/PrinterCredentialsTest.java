package com.campusfood.merchant.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

class PrinterCredentialsTest {
    @TempDir
    Path directory;
    private final JdbcTemplate db = mock(JdbcTemplate.class);
    private final byte[] key = new byte[32];

    private PrinterCredentials service() throws Exception {
        Path file = directory.resolve("master.key");
        Files.writeString(file, Base64.getEncoder().encodeToString(key));
        return new PrinterCredentials(db, file.toString());
    }

    private Map<String, Object> row(String sn, String pkey) throws Exception {
        var row = new LinkedHashMap<String, Object>();
        row.put("app_id", "shared-app");
        row.put("sn", sn);
        row.put("name", sn + "店铺");
        encrypted(row, "app", "printer_appsecret", "shared-secret");
        encrypted(row, "device", "merchant_printer_pkey", pkey);
        return row;
    }

    private void encrypted(Map<String, Object> row, String prefix, String purpose, String value) throws Exception {
        byte[] nonce = new byte[12];
        var cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(purpose.getBytes(StandardCharsets.UTF_8));
        row.put(prefix + "Purpose", purpose);
        row.put(prefix + "Nonce", nonce);
        row.put(prefix + "Ciphertext", cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        row.put(prefix + "Fingerprint", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key)));
    }

    @Test
    void sharesAppCredentialsAndLoadsDifferentDeviceCredentialsPerMerchant() throws Exception {
        when(db.queryForList(anyString(), eq("a"))).thenReturn(List.of(row("SN-A", "key-a")));
        when(db.queryForList(anyString(), eq("b"))).thenReturn(List.of(row("SN-B", "key-b")));
        var service = service();
        var a = service.load("a");
        var b = service.load("b");
        assertEquals(a.appId(), b.appId());
        assertEquals(a.appSecret(), b.appSecret());
        assertEquals("shared-secret", a.appSecret());
        assertEquals("SN-A", a.sn());
        assertEquals("key-a", a.pkey());
        assertEquals("SN-B", b.sn());
        assertEquals("key-b", b.pkey());
    }

    @Test
    void rejectsWrongPurposeWithoutExposingSensitiveData() throws Exception {
        var row = row("SN", "pkey");
        row.put("devicePurpose", "printer_appsecret");
        when(db.queryForList(anyString(), eq("a"))).thenReturn(List.of(row));
        var service = service();
        var failure = assertThrows(ResponseStatusException.class, () -> service.load("a"));
        assertFalse(failure.getMessage().contains("pkey"));
        assertFalse(failure.getMessage().contains(directory.toString()));
    }
}

package com.campusfood.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.campusfood.user.WechatConfigurationService.PaymentCredentials;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

class WechatPayClientTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String API_KEY = "01234567890123456789012345678901";
    private static KeyPair keys;
    private final WechatPayClient client = new WechatPayClient(JSON);

    @BeforeAll
    static void keys() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keys = generator.generateKeyPair();
    }

    @Test
    void verifiesSignatureAndDecryptsOriginalNotification() throws Exception {
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        String body = notification();
        var result = client.notification(credentials(), timestamp, "header-nonce", "PUB_KEY_ID_TEST",
            sign(timestamp, body), body);
        assertEquals("SUCCESS", result.path("trade_state").asText());
    }

    @Test
    void rejectsModifiedOrExpiredNotifications() throws Exception {
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        String expired = Long.toString(Instant.now().minusSeconds(301).getEpochSecond());
        String body = notification();
        String validSignature = sign(timestamp, body);
        String expiredSignature = sign(expired, body);
        assertThrows(ResponseStatusException.class, () -> client.notification(credentials(), timestamp,
            "header-nonce", "PUB_KEY_ID_TEST", validSignature, body + " "));
        assertThrows(ResponseStatusException.class, () -> client.notification(credentials(), expired,
            "header-nonce", "PUB_KEY_ID_TEST", expiredSignature, body));
    }

    private static PaymentCredentials credentials() {
        return new PaymentCredentials(1, "wx-test", "123456", "serial", "PUB_KEY_ID_TEST", "https://example.test",
            API_KEY, Base64.getEncoder().encodeToString(keys.getPrivate().getEncoded()),
            Base64.getEncoder().encodeToString(keys.getPublic().getEncoded()));
    }

    private static String notification() throws Exception {
        String nonce = "123456789012";
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(bytes(API_KEY), "AES"), new GCMParameterSpec(128, bytes(nonce)));
        cipher.updateAAD(bytes("transaction"));
        String ciphertext = Base64.getEncoder().encodeToString(cipher.doFinal(bytes("{\"trade_state\":\"SUCCESS\"}")));
        return JSON.writeValueAsString(Map.of("event_type", "TRANSACTION.SUCCESS", "resource", Map.of(
            "algorithm", "AEAD_AES_256_GCM", "nonce", nonce, "ciphertext", ciphertext, "associated_data", "transaction")));
    }

    private static String sign(String timestamp, String body) throws Exception {
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(keys.getPrivate());
        signer.update(bytes(timestamp + "\nheader-nonce\n" + body + "\n"));
        return Base64.getEncoder().encodeToString(signer.sign());
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}

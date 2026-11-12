package com.campusfood.user;

import com.campusfood.user.WechatConfigurationService.PaymentCredentials;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
class WechatPayClient {
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    WechatPayClient(ObjectMapper json) {
        this.json = json;
    }

    String prepay(PaymentCredentials config, String id, String openid, long amount, Instant expiresAt) {
        String expiry = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(expiresAt.atOffset(ZoneOffset.UTC).withNano(0));
        JsonNode response = exchange(config, "POST", "/v3/pay/transactions/jsapi", Map.of(
            "appid", config.appId(), "mchid", config.merchant(), "description", "寝食记校园餐品",
            "out_trade_no", id, "time_expire", expiry, "notify_url", config.callback(),
            "payer", Map.of("openid", openid), "amount", Map.of("total", amount, "currency", "CNY")));
        String prepayId = response.path("prepay_id").asText();
        if (prepayId.isBlank() || prepayId.length() > 128) throw WechatConfigurationService.paymentUnavailable();
        return prepayId;
    }

    JsonNode query(PaymentCredentials config, String id) {
        return exchange(config, "GET", "/v3/pay/transactions/out-trade-no/" + id + "?mchid=" + config.merchant(), null);
    }

    void close(PaymentCredentials config, String id) {
        JsonNode result = exchange(config, "POST", "/v3/pay/transactions/out-trade-no/" + id + "/close",
            Map.of("mchid", config.merchant()));
        if (!result.isEmpty()) throw WechatConfigurationService.paymentUnavailable();
    }

    JsonNode refund(PaymentCredentials config, String transactionId, String refundId, long amount, long total) {
        return exchange(config, "POST", "/v3/refund/domestic/refunds", Map.of(
            "transaction_id", transactionId, "out_refund_no", refundId,
            "amount", Map.of("refund", amount, "total", total, "currency", "CNY")));
    }

    JsonNode queryRefund(PaymentCredentials config, String refundId) {
        return exchange(config, "GET", "/v3/refund/domestic/refunds/" + refundId, null);
    }

    JsonNode riderTransfer(PaymentCredentials config, Map<String, Object> payload) {
        return exchange(config, "POST", "/v3/fund-app/mch-transfer/transfer-bills", payload);
    }

    JsonNode queryRiderTransfer(PaymentCredentials config, String bill) {
        return exchange(config, "GET", "/v3/fund-app/mch-transfer/transfer-bills/out-bill-no/" + bill, null);
    }

    String encryptRecipient(PaymentCredentials config, String name) {
        try {
            var key = KeyFactory.getInstance("RSA").generatePublic(
                new X509EncodedKeySpec(pem(config.publicKey(), "PUBLIC KEY")));
            Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding");
            cipher.init(Cipher.ENCRYPT_MODE, key);
            return Base64.getEncoder().encodeToString(cipher.doFinal(bytes(name)));
        } catch (Exception error) {
            throw WechatConfigurationService.paymentUnavailable();
        }
    }

    Map<String, String> parameters(PaymentCredentials config, String prepayId) {
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        String nonce = nonce();
        String packet = "prepay_id=" + prepayId;
        return Map.of("timeStamp", timestamp, "nonceStr", nonce, "package", packet, "signType", "RSA",
            "paySign", sign(config, lines(config.appId(), timestamp, nonce, packet)));
    }

    JsonNode notification(PaymentCredentials config, String timestamp, String nonce, String serial, String signature,
            String body) {
        try {
            verify(config, timestamp, nonce, serial, signature, body);
            JsonNode event = json.readTree(body);
            JsonNode resource = event.path("resource");
            if (!"TRANSACTION.SUCCESS".equals(event.path("event_type").asText())
                    || !"AEAD_AES_256_GCM".equals(resource.path("algorithm").asText())) throw new IllegalArgumentException();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(bytes(config.apiKey()), "AES"),
                new GCMParameterSpec(128, bytes(resource.path("nonce").asText())));
            cipher.updateAAD(bytes(resource.path("associated_data").asText()));
            return json.readTree(cipher.doFinal(Base64.getDecoder().decode(resource.path("ciphertext").asText())));
        } catch (Exception error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "微信支付通知验证失败");
        }
    }

    private JsonNode exchange(PaymentCredentials config, String method, String path, Object payload) {
        try {
            String body = payload == null ? "" : json.writeValueAsString(payload);
            String time = Long.toString(Instant.now().getEpochSecond());
            String nonce = nonce();
            String signature = sign(config, lines(method, path, time, nonce, body));
            String authorization = "WECHATPAY2-SHA256-RSA2048 mchid=\"" + config.merchant()
                + "\",nonce_str=\"" + nonce + "\",timestamp=\"" + time + "\",serial_no=\"" + config.serial()
                + "\",signature=\"" + signature + "\"";
            var request = HttpRequest.newBuilder(URI.create("https://api.mch.weixin.qq.com" + path))
                .timeout(Duration.ofSeconds(5)).header("Accept", "application/json").header("Content-Type", "application/json")
                .header("Authorization", authorization).header("Wechatpay-Serial", config.publicKeyId())
                .method(method, payload == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            var headers = response.headers();
            // 查单、关单和预支付均依赖渠道签名，超时或无法验证时保留原订单状态。
            verify(config, headers.firstValue("Wechatpay-Timestamp").orElse(""),
                headers.firstValue("Wechatpay-Nonce").orElse(""), headers.firstValue("Wechatpay-Serial").orElse(""),
                headers.firstValue("Wechatpay-Signature").orElse(""), response.body());
            if (response.statusCode() == 204) return json.createObjectNode();
            JsonNode result = json.readTree(response.body());
            if (response.statusCode() >= 200 && response.statusCode() < 300) return result;
            if (response.statusCode() == 404
                    && java.util.Set.of("ORDER_NOT_EXIST", "RESOURCE_NOT_EXISTS", "NOT_FOUND").contains(result.path("code").asText())) {
                return result;
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        } catch (Exception error) {
            throw WechatConfigurationService.paymentUnavailable();
        }
        throw WechatConfigurationService.paymentUnavailable();
    }

    private static void verify(PaymentCredentials config, String timestamp, String nonce, String serial,
            String signature, String body) throws Exception {
        long time = Long.parseLong(timestamp);
        long now = Instant.now().getEpochSecond();
        if (!config.publicKeyId().equals(serial) || nonce.isBlank() || time < now - 300 || time > now + 300) {
            throw new IllegalArgumentException("微信签名头无效");
        }
        var key = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(pem(config.publicKey(), "PUBLIC KEY")));
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(key);
        verifier.update(bytes(lines(timestamp, nonce, body)));
        if (!verifier.verify(Base64.getDecoder().decode(signature))) throw new IllegalArgumentException("微信签名不匹配");
    }

    private static String sign(PaymentCredentials config, String message) {
        try {
            var key = KeyFactory.getInstance("RSA").generatePrivate(
                new PKCS8EncodedKeySpec(pem(config.merchantKey(), "PRIVATE KEY")));
            Signature signer = Signature.getInstance("SHA256withRSA");
            signer.initSign(key);
            signer.update(bytes(message));
            return Base64.getEncoder().encodeToString(signer.sign());
        } catch (Exception error) {
            throw WechatConfigurationService.paymentUnavailable();
        }
    }

    private static byte[] pem(String value, String type) {
        return Base64.getDecoder().decode(value.replace("-----BEGIN " + type + "-----", "")
            .replace("-----END " + type + "-----", "").replaceAll("\\s", ""));
    }

    private static String lines(String... values) {
        return String.join("\n", values) + "\n";
    }

    private static String nonce() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}

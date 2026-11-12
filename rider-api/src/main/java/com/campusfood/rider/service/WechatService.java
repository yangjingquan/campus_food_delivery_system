package com.campusfood.rider.service;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
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
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@Service
public class WechatService {
    private static final String PURPOSE = "rider_appsecret";

    private final JdbcTemplate db;
    private final String appId;
    private final String keyFile;
    private final RestClient client;
    private final ObjectMapper json;

    public WechatService(JdbcTemplate db, ObjectMapper json, @Value("${app.wechat-app-id}") String appId,
                         @Value("${app.wechat-master-key-file}") String keyFile) {
        this.db = db;
        this.json = json;
        this.appId = appId;
        this.keyFile = keyFile;
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(4)).build());
        factory.setReadTimeout(Duration.ofSeconds(8));
        this.client = RestClient.builder().requestFactory(factory).baseUrl("https://api.weixin.qq.com").build();
    }

    String appId() {
        return appId;
    }

    String exchange(String code) {
        String secret = secret();
        Map<?, ?> result;
        try {
            String body = client.get().uri(builder -> builder.path("/sns/jscode2session")
                .queryParam("appid", "{appId}").queryParam("secret", "{secret}")
                .queryParam("js_code", "{code}").queryParam("grant_type", "authorization_code")
                .build(appId, secret, code)).retrieve().body(String.class);
            // 微信也会以 text/plain 返回 JSON，先读取正文再解析。
            result = body == null ? null : json.readValue(body, Map.class);
        } catch (Exception ignored) {
            // HTTP 异常可能含完整查询地址，避免将 AppSecret 写入日志或响应。
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "微信登录暂不可用，请稍后重试");
        }
        if (result != null && result.get("openid") instanceof String openId
                && openId.matches("[A-Za-z0-9_-]{1,128}")) {
            return openId;
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "微信登录凭证失效，请重新登录");
    }

    private String accessToken;
    private long accessTokenExpiresAt;

    private synchronized String accessToken() {
        long now = System.currentTimeMillis();
        if (accessToken != null && accessTokenExpiresAt > now) {
            return accessToken;
        }
        try {
            String response = client.post().uri("/cgi-bin/stable_token").body(Map.of(
                "grant_type", "client_credential", "appid", appId, "secret", secret(), "force_refresh", false
            )).retrieve().body(String.class);
            var result = json.readTree(response);
            String token = result.path("access_token").asText();
            int ttl = result.path("expires_in").asInt();
            if (!token.isBlank() && ttl > 60) {
                accessToken = token;
                accessTokenExpiresAt = now + (ttl - 60L) * 1000L;
                return token;
            }
        } catch (RuntimeException ignored) {
            // 微信 HTTP 异常不对外暴露请求参数。
        }
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "微信订阅消息服务暂不可用");
    }

    int sendReminder(String openid, String templateId, Map<String, Object> fields) {
        try {
            String token = accessToken();
            String response = client.post().uri("/cgi-bin/message/subscribe/send?access_token={token}", token)
                .body(Map.of("touser", openid, "template_id", templateId, "page", "pages/waiting/index",
                    "lang", "zh_CN", "data", fields)).retrieve().body(String.class);
            int code = json.readTree(response).path("errcode").asInt(-1);
            if (code == 40001 || code == 42001) {
                synchronized (this) {
                    accessToken = null;
                }
            }
            return code;
        } catch (RuntimeException exception) {
            return -1;
        }
    }

    private String secret() {
        byte[] key = null;
        byte[] clear = null;
        try {
            // 与管理端保存配置使用同一密钥文件和 AES-GCM 用途标识。
            Map<String, Object> row = db.queryForMap("""
                SELECT c.rider_app_id,s.purpose,s.nonce,s.ciphertext,s.key_fingerprint
                FROM wechat_config c LEFT JOIN wechat_secret s ON s.id=c.rider_secret_id
                ORDER BY c.id DESC LIMIT 1
                """);
            if (!appId.equals(row.get("rider_app_id")) || !PURPOSE.equals(row.get("purpose"))) {
                throw new IllegalStateException();
            }
            key = Base64.getDecoder().decode(Files.readString(Path.of(keyFile)).trim());
            byte[] nonce = (byte[]) row.get("nonce");
            String expected = Objects.toString(row.get("key_fingerprint"), "");
            String fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key));
            if (key.length != 32 || nonce.length != 12 || (!expected.isEmpty() && !expected.equals(fingerprint))) {
                throw new IllegalStateException();
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(PURPOSE.getBytes(StandardCharsets.UTF_8));
            clear = cipher.doFinal((byte[]) row.get("ciphertext"));
            String secret = new String(clear, StandardCharsets.UTF_8);
            if (!secret.isBlank()) {
                return secret;
            }
        } catch (Exception ignored) {
            // 对外统一返回配置不可用，详情不包含密钥、密文或文件路径。
        } finally {
            if (key != null) Arrays.fill(key, (byte) 0);
            if (clear != null) Arrays.fill(clear, (byte) 0);
        }
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "管理员后台的骑手小程序微信配置不可用");
    }
}

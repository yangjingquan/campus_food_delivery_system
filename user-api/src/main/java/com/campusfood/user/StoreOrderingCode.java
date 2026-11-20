package com.campusfood.user;

import java.time.Duration;
import java.util.Base64;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@Service
class StoreOrderingCode {
    private static final Logger LOG = LoggerFactory.getLogger(StoreOrderingCode.class);
    private static final String API = "https://api.weixin.qq.com";

    private final JdbcTemplate db;
    private final WechatConfigurationService configuration;
    private final ObjectMapper json;
    private final RestClient http;
    private Token cached;

    private record Token(WechatConfigurationService.Credentials owner, String value, long expiresAt) {}

    @Autowired
    StoreOrderingCode(JdbcTemplate db, WechatConfigurationService configuration, ObjectMapper json) {
        this(db, configuration, json, client());
    }

    StoreOrderingCode(JdbcTemplate db, WechatConfigurationService configuration, ObjectMapper json, RestClient http) {
        this.db = db;
        this.configuration = configuration;
        this.json = json;
        this.http = http;
    }

    private static RestClient client() {
        var factory = new JdkClientHttpRequestFactory(java.net.http.HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3)).build());
        factory.setReadTimeout(Duration.ofSeconds(4));
        return RestClient.builder().requestFactory(factory).build();
    }

    Map<String, String> image(String merchant) {
        // 商户编号即场景参数，历史短编号和当前 32 位编号都可用于同一个小程序页面。
        if (!merchant.matches("[A-Za-z0-9_-]{1,32}") || db.queryForList(
                "SELECT id FROM merchant WHERE id=? AND audit_status='已通过'", String.class, merchant).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "店铺不存在或尚未通过审核");
        }
        var credentials = configuration.credentials();
        try {
            String token = token(credentials, null);
            for (int attempt = 0; attempt < 2; attempt++) {
                var response = http.post()
                        .uri(API + "/wxa/getwxacodeunlimit?access_token={token}", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("scene", merchant, "page", "pages/store/index", "env_version", "release",
                                     "check_path", true, "width", 430))
                        .retrieve().toEntity(byte[].class);
                var content = response.getBody();
                var type = response.getHeaders().getContentType();
                if (content != null && content.length > 0 && type != null
                        && (MediaType.IMAGE_PNG.isCompatibleWith(type) || MediaType.IMAGE_JPEG.isCompatibleWith(type))) {
                    String media = MediaType.IMAGE_PNG.isCompatibleWith(type) ? "image/png" : "image/jpeg";
                    return Map.of("content", Base64.getEncoder().encodeToString(content), "mediaType", media);
                }
                int errorCode = content == null ? 0 : json.readTree(content).path("errcode").asInt();
                if (attempt == 0 && (errorCode == 40001 || errorCode == 40014 || errorCode == 42001)) {
                    token = token(credentials, token);
                    continue;
                }
                LOG.warn("微信生成店铺点餐码失败，错误码 {}", errorCode);
                break;
            }
        } catch (RestClientException | tools.jackson.core.JacksonException error) {
            // HTTP 异常可能带访问令牌，只记录异常类型。
            LOG.warn("微信店铺点餐码请求失败，异常类型 {}", error.getClass().getSimpleName());
        }
        throw unavailable();
    }

    private synchronized String token(WechatConfigurationService.Credentials credentials, String rejected) {
        long now = System.currentTimeMillis();
        if (cached != null && cached.owner().equals(credentials) && cached.expiresAt() > now
                && !cached.value().equals(rejected)) {
            return cached.value();
        }
        String body = http.post().uri(API + "/cgi-bin/stable_token").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("grant_type", "client_credential", "appid", credentials.appId(),
                             "secret", credentials.appSecret(), "force_refresh", rejected != null))
                .retrieve().body(String.class);
        var result = body == null ? null : json.readTree(body);
        String value = result == null ? "" : result.path("access_token").asText("");
        int seconds = result == null ? 0 : result.path("expires_in").asInt();
        if (value.isBlank() || seconds <= 0) {
            LOG.warn("微信获取店铺点餐码访问令牌失败，错误码 {}", result == null ? 0 : result.path("errcode").asInt());
            throw unavailable();
        }
        cached = new Token(credentials, value, now + Math.max(0, seconds - 60) * 1000L);
        return value;
    }

    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "微信点餐码暂不可用，请确认用户端小程序已发布");
    }
}

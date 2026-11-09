package com.campusfood.user;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class WechatIdentityService {
    private static final Logger LOGGER = LoggerFactory.getLogger(WechatIdentityService.class);
    private final RestClient http;
    private final WechatConfigurationService configuration;
    private final ObjectMapper json;
    @Value("${app.wechat.mock-login:false}") private boolean mockLogin;

    @Autowired
    public WechatIdentityService(WechatConfigurationService configuration, ObjectMapper json) {
        this(configuration, json, RestClient.create());
    }

    WechatIdentityService(WechatConfigurationService configuration, ObjectMapper json, RestClient http) {
        this.configuration = configuration;
        this.json = json;
        this.http = http;
    }

    public String exchangeCode(String code) {
        if (code == null || code.isBlank() || code.length() > 512) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "微信登录凭证无效");
        }
        if (mockLogin) return "local:" + sha256(configuration.currentAppId() + ":" + code);
        var credentials = configuration.credentials();
        JsonNode response;
        try {
            String query = "appid=" + enc(credentials.appId()) + "&secret=" + enc(credentials.appSecret()) + "&js_code=" + enc(code) + "&grant_type=authorization_code";
            String body = http.get()
                .uri(URI.create("https://api.weixin.qq.com/sns/jscode2session?" + query))
                .retrieve()
                .body(String.class);
            // 微信可能以 text/plain 返回 JSON，先读取正文再解析。
            response = body == null ? null : json.readTree(body);
        } catch (RestClientResponseException error) {
            LOGGER.warn("微信登录上游请求失败，HTTP 状态码 {}", error.getStatusCode().value());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "微信登录服务暂不可用");
        } catch (RestClientException | JacksonException error) {
            // 异常消息可能包含带 AppSecret 的请求地址，仅记录异常类型。
            Throwable cause = error.getCause();
            LOGGER.warn("微信登录上游通信或响应解析失败，异常类型 {}",
                (cause == null ? error : cause).getClass().getSimpleName());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "微信登录服务暂不可用");
        }
        if (response != null) {
            JsonNode openId = response.path("openid");
            if (openId.isTextual() && !openId.stringValue().isBlank()) return openId.stringValue();
            JsonNode errorCode = response.path("errcode");
            if (errorCode.isIntegralNumber()) LOGGER.warn("微信拒绝登录凭证，错误码 {}", errorCode.intValue());
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "微信登录未通过，请重试");
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }
}

package com.campusfood.user;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

@Service
public class WechatIdentityService {
    private final RestClient http = RestClient.create();
    private final WechatConfigurationService configuration;
    @Value("${app.wechat.mock-login:false}") private boolean mockLogin;

    public WechatIdentityService(WechatConfigurationService configuration) {
        this.configuration = configuration;
    }

    public String exchangeCode(String code) {
        if (code == null || code.isBlank() || code.length() > 512) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "微信登录凭证无效");
        }
        if (mockLogin) return "local:" + sha256(configuration.currentAppId() + ":" + code);
        var credentials = configuration.credentials();
        try {
            String query = "appid=" + enc(credentials.appId()) + "&secret=" + enc(credentials.appSecret()) + "&js_code=" + enc(code) + "&grant_type=authorization_code";
            Map<String, Object> response = http.get()
                .uri(URI.create("https://api.weixin.qq.com/sns/jscode2session?" + query))
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
            if (response == null || !(response.get("openid") instanceof String openid) || openid.isBlank()) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "微信登录未通过，请重试");
            }
            return openid;
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "微信登录服务暂不可用");
        }
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

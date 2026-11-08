package com.campusfood.merchant.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;

import org.springframework.http.HttpStatus;
import com.campusfood.merchant.dao.MerchantSessionMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SessionService {
    private final MerchantSessionMapper sessions;
    private final WechatService wechat;
    private final SecureRandom random = new SecureRandom();

    public record Identity(long id, String merchantId, String auditStatus) {
        public String approvedStore() {
            if (!"已通过".equals(auditStatus)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "店铺通过审核后可使用该功能");
            }
            return merchantId;
        }
    }

    public SessionService(MerchantSessionMapper sessions, WechatService wechat) {
        this.sessions = sessions;
        this.wechat = wechat;
    }

    public Map<String, Object> login(Map<String, Object> body) {
        String openId = wechat.exchange(Inputs.text(body, "code", "微信登录凭证", 256, true));
        sessions.ensureIdentity(wechat.appId(), openId);
        long identityId = sessions.identityId(wechat.appId(), openId);
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        long expiresAt = System.currentTimeMillis() + Duration.ofHours(24).toMillis();
        sessions.save(digest(token), identityId, expiresAt);
        return Map.of("token", token, "expiresAt", expiresAt);
    }

    public Identity require(String header) {
        Map<String, Object> row = sessions.findActive(digest(token(header)), System.currentTimeMillis());
        if (row == null) throw unauthorized();
        return new Identity(((Number) row.get("id")).longValue(), (String) row.get("merchant_id"),
            (String) row.get("audit_status"));
    }

    public void logout(String header) {
        sessions.revoke(digest(token(header)));
    }

    @Scheduled(fixedDelay = 3600000)
    void expireSessions() {
        sessions.expire(System.currentTimeMillis());
    }

    private static String token(String header) {
        if (header == null || !header.matches("Bearer [A-Za-z0-9_-]{43}")) {
            throw unauthorized();
        }
        return header.substring(7);
    }

    private static ResponseStatusException unauthorized() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请重新登录商户小程序");
    }

    private static String digest(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(token.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

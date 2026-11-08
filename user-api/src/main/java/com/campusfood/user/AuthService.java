package com.campusfood.user;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AuthService {
    public record Principal(long userId, String displayName) {}
    private final JdbcTemplate db;
    private final WechatIdentityService wechat;
    private final SecureRandom random = new SecureRandom();
    @Value("${app.session-hours:24}") private int sessionHours;

    public AuthService(JdbcTemplate db, WechatIdentityService wechat) {
        this.db = db;
        this.wechat = wechat;
    }

    @Transactional
    public Map<String, Object> login(String code) {
        String openid = wechat.exchangeCode(code);
        Long userId = db.query("SELECT id FROM user_profile WHERE openid=?", rs -> rs.next() ? rs.getLong(1) : null, openid);
        if (userId == null) {
            try {
                db.update("INSERT INTO user_profile(openid) VALUES(?)", openid);
            } catch (DuplicateKeyException ignored) {
                // A simultaneous login may have created the profile first.
            }
            userId = db.queryForObject("SELECT id FROM user_profile WHERE openid=?", Long.class, openid);
        }

        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant expires = Instant.now().plus(sessionHours, ChronoUnit.HOURS);
        db.update("INSERT INTO user_session(token_hash,user_id,expires_at) VALUES(?,?,?)", hash(token), userId, Timestamp.from(expires));
        String name = db.queryForObject("SELECT display_name FROM user_profile WHERE id=?", String.class, userId);
        return Map.of("token", token, "expiresAt", expires.toEpochMilli(), "user", Map.of("id", userId, "displayName", name));
    }

    public Principal require(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ") || header.length() <= 7) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
        }
        return principal(header.substring(7));
    }

    public Principal principal(String token) {
        if (token == null || token.length() < 20 || token.length() > 256) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已失效，请重新登录");
        }
        var rows = db.queryForList("SELECT p.id,p.display_name FROM user_session s JOIN user_profile p ON p.id=s.user_id WHERE s.token_hash=? AND s.revoked=FALSE AND s.expires_at>CURRENT_TIMESTAMP", hash(token));
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已失效，请重新登录");
        return new Principal(((Number) rows.get(0).get("id")).longValue(), rows.get(0).get("display_name").toString());
    }

    @Transactional
    public void logout(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        Principal user = require(request);
        db.update("UPDATE user_session SET revoked=TRUE WHERE token_hash=? AND user_id=?", hash(header.substring(7)), user.userId());
    }

    private static String hash(String token) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }
}

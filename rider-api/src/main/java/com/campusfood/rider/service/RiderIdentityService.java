package com.campusfood.rider.service;

import com.campusfood.rider.dao.RiderDao;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RiderIdentityService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Duration SESSION_LIFETIME = Duration.ofDays(7);
    private final JdbcTemplate db;
    private final RiderDao riders;
    private final WechatService wechat;

    public RiderIdentityService(JdbcTemplate db, RiderDao riders, WechatService wechat) {
        this.db = db;
        this.riders = riders;
        this.wechat = wechat;
    }

    @Transactional
    public Map<String, Object> login(String code) {
        String openid = wechat.exchange(Inputs.required(code, 256, "微信登录凭证"));
        String candidate = "R" + UUID.randomUUID().toString().replace("-", "").substring(0, 30);
        db.update("""
            INSERT INTO rider_identity(id,app_id,openid) VALUES(?,?,?)
            ON DUPLICATE KEY UPDATE id=id
            """, candidate, wechat.appId(), openid);
        String id = db.queryForObject("SELECT id FROM rider_identity WHERE app_id=? AND openid=?",
            String.class, wechat.appId(), openid);
        byte[] random = new byte[32];
        RANDOM.nextBytes(random);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        db.update("INSERT INTO rider_session(token_hash,rider_id,expires_at) VALUES(?,?,?)",
            hash(token), id, System.currentTimeMillis() + SESSION_LIFETIME.toMillis());
        return Map.of("token", token, "profile", profile(id));
    }

    public String authenticate(String authorization) {
        String token = token(authorization);
        return db.queryForList("""
            SELECT rider_id FROM rider_session WHERE token_hash=? AND expires_at>?
            """, String.class, hash(token), System.currentTimeMillis()).stream().findFirst()
            .orElseThrow(RiderIdentityService::unauthorized);
    }

    public void logout(String authorization) {
        db.update("DELETE FROM rider_session WHERE token_hash=?", hash(token(authorization)));
    }

    @Scheduled(fixedDelay = 3_600_000)
    void removeExpiredSessions() {
        db.update("DELETE FROM rider_session WHERE expires_at<=?", System.currentTimeMillis());
    }

    public Map<String, Object> profile(String id) {
        var profile = riders.profile(id);
        if (profile.get("auditStatus") == null) {
            profile.put("auditStatus", "未入驻");
        }
        return profile;
    }

    public void requireApproved(String id) {
        if (!"已通过".equals(profile(id).get("auditStatus"))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "学生身份审核通过后才能开展配送");
        }
    }

    @Transactional
    public Map<String, Object> apply(String id, String name, String phone, String gender,
                                     String availableTime, MultipartFile studentCard) throws IOException {
        name = Inputs.required(name, 80, "姓名");
        phone = Inputs.required(phone, 20, "手机号");
        availableTime = DeliveryAvailability.validate(Inputs.required(availableTime, 160, "可送餐时间"));
        if (!phone.matches("1[3-9]\\d{9}")) {
            throw Inputs.invalid("请输入有效的手机号");
        }
        if (!List.of("男", "女", "不便透露").contains(gender)) {
            throw Inputs.invalid("请选择性别");
        }
        if (studentCard.isEmpty() || studentCard.getSize() > 4 * 1024 * 1024) {
            throw Inputs.invalid("请上传 4 MB 以内的学生证照片");
        }
        byte[] bytes = studentCard.getBytes();
        String type = imageType(bytes);

        // 同一微信身份串行提交，驳回后可重交，待审核和通过的资料禁止覆盖。
        db.queryForObject("SELECT id FROM rider_identity WHERE id=? FOR UPDATE", String.class, id);
        var statuses = db.queryForList("SELECT audit_status FROM rider WHERE id=? FOR UPDATE", String.class, id);
        if (statuses.isEmpty()) {
            String now = LocalDateTime.now(ZoneId.of("Asia/Shanghai"))
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            db.update("""
                INSERT INTO rider(id,name,phone,gender,available_time,audit_status,joined_at)
                VALUES(?,?,?,?,?,'待审核',?)
                """, id, name, phone, gender, availableTime, now);
            db.update("INSERT INTO finance_account(id,name,type,status) VALUES(?,?,'骑手','正常')", id, name);
        } else {
            if (!"已驳回".equals(statuses.getFirst())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "当前审核状态不可重复提交");
            }
            db.update("""
                UPDATE rider SET name=?,phone=?,gender=?,available_time=?,audit_status='待审核',audit_reason=NULL
                WHERE id=?
                """, name, phone, gender, availableTime, id);
            db.update("UPDATE finance_account SET name=? WHERE id=?", name, id);
        }
        db.update("""
            INSERT INTO rider_student_card(rider_id,media_type,image_data) VALUES(?,?,?)
            ON DUPLICATE KEY UPDATE media_type=?,image_data=?
            """, id, type, bytes, type, bytes);
        return profile(id);
    }

    private static String imageType(byte[] bytes) {
        if (bytes.length > 8 && HexFormat.of().formatHex(bytes, 0, 8).equals("89504e470d0a1a0a")) {
            return "image/png";
        }
        if (bytes.length > 4 && HexFormat.of().formatHex(bytes, 0, 3).equals("ffd8ff")) {
            return "image/jpeg";
        }
        throw Inputs.invalid("学生证照片仅支持 JPEG 或 PNG");
    }

    private static String token(String authorization) {
        if (authorization == null || !authorization.matches("Bearer [A-Za-z0-9_-]{43}")) {
            throw unauthorized();
        }
        return authorization.substring(7);
    }

    private static ResponseStatusException unauthorized() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请重新进行微信登录");
    }

    private static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(token.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}

package com.campusfood.admin.service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import com.campusfood.admin.dao.AdminDao;
import com.campusfood.admin.dao.ConfigurationLockMapper;
import com.campusfood.admin.dao.AdminUserRoleMapper;
import com.campusfood.admin.dao.MerchantMapper;
import com.campusfood.admin.dao.RiderMapper;
import com.campusfood.admin.dto.*;
import org.springframework.stereotype.Service;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.web.server.ResponseStatusException;

/** 管理员身份、权限和业务操作的服务入口。 */
@Service
public class AdminService {
    private final AdminDao db;
    private final ConfigurationLockMapper configurationLocks;
    private final AdminUserRoleMapper roles;
    private final MerchantMapper merchants;
    private final RiderMapper riders;
    private final StringRedisTemplate redis;
    private final BusinessService business;
    private final AuthorizationService authorization;
    private final WithdrawalService withdrawals;
    private final WechatService wechat;
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder();
    private final SecureRandom random = new SecureRandom();
    @Value("${app.bootstrap-user}")
    private String bootstrapUser;
    @Value("${app.bootstrap-password}")
    private String bootstrapPassword;
    @Value("${app.redis-key-prefix:}")
    private String redisKeyPrefix;

    public AdminService(AdminDao db, StringRedisTemplate redis, BusinessService business,
                        AuthorizationService authorization, WithdrawalService withdrawals, WechatService wechat,
                        AdminUserRoleMapper roles, MerchantMapper merchants, RiderMapper riders,
                        ConfigurationLockMapper configurationLocks) {
        this.db = db;
        this.configurationLocks = configurationLocks;
        this.roles = roles;
        this.merchants = merchants;
        this.riders = riders;
        this.redis = redis;
        this.business = business;
        this.authorization = authorization;
        this.withdrawals = withdrawals;
        this.wechat = wechat;
    }

    // 仅在账户或角色尚未初始化时创建本地引导管理员及超级管理员角色。
    @PostConstruct
    void bootstrap() {
        Integer count = db.countUsersByUsername(bootstrapUser);
        if (count != null && count == 0) db.insertUser(bootstrapUser, passwords.encode(bootstrapPassword));
        Integer roleCount = roles.countRoles();
        if (roleCount != null && roleCount == 0) db.assignBootstrapSuperRole(bootstrapUser);
    }

    private String actor(String authHeader) {
        String header = authHeader;
        if (header == null || !header.startsWith("Bearer "))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请登录");
        // Redis 保存用户名和会话版本；每次请求还需校验数据库中的最新版本。
        String session = redis.opsForValue().get(redisKeyPrefix + "admin:session:" + header.substring(7));
        if (session == null || !session.contains("\n"))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已失效");
        int separator = session.lastIndexOf('\n');
        String username = session.substring(0, separator), version = session.substring(separator + 1);
        Integer valid = db.countValidSessions(username, version);
        if (valid == null || valid != 1) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已失效");
        return username;
    }

    public Map<String, Object> login(LoginRequest body) {
        String username = valueOrEmpty(body.username());
        List<Map<String, Object>> users = db.findEnabledCredentials(username);
        if (users.isEmpty() ||
                !passwords.matches(valueOrEmpty(body.password()), users.get(0).get("password_hash").toString()))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "账号或密码错误");
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        redis.opsForValue().set(redisKeyPrefix + "admin:session:" + token, username + "\n" + users.get(0).get("session_version"),
                Duration.ofHours(8));
        return Map.of("token", token, "user",
                Map.of("name", username, "role", String.join("、", authorization.roles(username)), "permissions",
                        authorization.permissions(username)));
    }

    public Map<String, Object> logout(String authHeader) {
        actor(authHeader);
        redis.delete(redisKeyPrefix + "admin:session:" + authHeader.substring(7));
        return Map.of("ok", true);
    }

    private static String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }

    private static void validatePassword(String password) {
        if (password.length() < 12 || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "密码至少 12 个字符，UTF-8 编码不超过 72 字节");
    }

    @Transactional
    public Map<String, Object> changePassword(ChangePasswordRequest body, String authHeader) {
        String username = actor(authHeader), oldPassword = valueOrEmpty(body.oldPassword()), newPassword =
                valueOrEmpty(body.newPassword());
        validatePassword(newPassword);
        String hash = db.lockPasswordHash(username);
        if (!passwords.matches(oldPassword, hash))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "原密码错误");
        db.changePasswordByUsername(passwords.encode(newPassword), username);
        db.insertSimpleAudit(username, "admin.password", username, "已更新");
        return Map.of("ok", true);
    }

    public Map<String, Object> workspace(String section, String authHeader) {
        String username = actor(authHeader);
        String permission = switch (section) {
            case "merchants" -> "merchant.view";
            case "riders" -> "rider.view";
            case "orders" -> "order.view";
            case "aftersales" -> "refund.view";
            case "finance" -> "finance.view";
            case "withdrawals" -> "withdrawal.view";
            case "config" -> "config.view";
            case "wechat" -> "wechat.view";
            default -> throw new ResponseStatusException(HttpStatus.NOT_FOUND, "模块不存在");
        };
        authorization.require(username, permission);
        if (section.equals("wechat")) return wechat.workspace();
        Object orders =
                authorization.permissions(username).contains("order.view") ? business.workspace("orders").get("rows") :
                        List.of();
        if (section.equals("merchants")) return Map.of("rows", db.listMerchants(), "orders", orders);
        if (section.equals("riders")) return Map.of("rows", db.listRiders(), "orders", orders);
        return business.workspace(section);
    }

    public Map<String, Object> overview(String authHeader) {
        String username = actor(authHeader);
        authorization.require(username, "overview.view");
        return business.overview();
    }

    public Map<String, Object> administrators(String authHeader) {
        authorization.require(actor(authHeader), "admin.manage");
        return Map.of("users", db.listAdministrators(), "roles", db.listRoles());
    }

    @Transactional
    public Map<String, Object> createAdministrator(CreateAdministratorRequest body, String authHeader) {
        String actor = actor(authHeader);
        authorization.require(actor, "admin.manage");
        String username = valueOrEmpty(body.username()).trim(), password = valueOrEmpty(body.password()), role =
                valueOrEmpty(body.role());
        if (!username.matches("[A-Za-z0-9_.-]{3,64}") ||
                !List.of("super", "operations", "finance", "readonly").contains(role))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "账号或角色格式无效");
        validatePassword(password);
        if (db.countUsersByUsername(username) > 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "账号已存在");
        try {
            db.insertUser(username, passwords.encode(password));
        } catch (org.springframework.dao.DuplicateKeyException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "账号已存在");
        }
        db.assignRoleByUsername(role, username);
        db.insertSimpleAudit(actor, "admin.create", username, role);
        return Map.of("ok", true);
    }

    @Transactional
    public Map<String, Object> changeAdministrator(long id, AdministratorAccessRequest body, String authHeader) {
        String actor = actor(authHeader);
        authorization.require(actor, "admin.manage");
        String role = String.valueOf(body.role());
        Object enabledValue = body.enabled();
        if (!List.of("super", "operations", "finance", "readonly").contains(role) ||
                !(enabledValue instanceof Boolean enabled))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "角色或启用状态无效");
        // 先锁定管理员配置，避免并发修改导致最后一名超级管理员被移除。
        configurationLocks.lock("admin");
        List<Map<String, Object>> users = db.lockAdministrator(id);
        if (users.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "管理员不存在");
        Integer isSuper = db.countEnabledSuperById(id);
        Integer supers = db.countEnabledSupers();
        if (isSuper != null && isSuper > 0 && (!enabled || !role.equals("super")) && supers != null && supers <= 1)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "必须保留至少一名启用的超级管理员");
        db.setAdministratorEnabled(enabled, id);
        roles.deleteRoles(id);
        db.assignRoleById(id, role);
        db.insertSimpleAudit(actor, "admin.access", users.get(0).get("username"), role + (enabled ? "/启用" : "/停用"));
        return Map.of("ok", true);
    }

    @Transactional
    public Map<String, Object> resetAdministratorPassword(long id, ResetPasswordRequest body, String authHeader) {
        String actor = actor(authHeader);
        authorization.require(actor, "admin.manage");
        String password = valueOrEmpty(body.password());
        validatePassword(password);
        List<String> names = db.lockUsernameById(id);
        if (names.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "管理员不存在");
        db.resetPasswordById(passwords.encode(password), id);
        db.insertSimpleAudit(actor, "admin.password-reset", names.get(0), "已重置");
        return Map.of("ok", true);
    }

    @Transactional
    public Map<String, Object> action(AdminActionRequest body, String authHeader) {
        String username = actor(authHeader);
        String type = String.valueOf(body.type());
        String permission = switch (type) {
            case "merchant.audit" -> "merchant.audit";
            case "rider.audit" -> "rider.audit";
            case "order.coordinate" -> "order.coordinate";
            case "refund.apply", "refund.offline", "refund.query" -> "refund.manage";
            case "withdrawal.review" -> "withdrawal.review";
            case "config.save" -> "config.edit";
            case "wechat.save-app", "wechat.save-payment", "wechat.rollback" -> "wechat.edit";
            case "wechat.toggle" -> "wechat.toggle";
            default -> throw new ResponseStatusException(HttpStatus.NOT_FOUND, "操作不存在");
        };
        authorization.require(username, permission);
        if (!(body.payload() instanceof Map<?, ?> payload))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少审核信息");
        // 回滚可能影响支付开关，因此同时要求修改和启停权限。
        if (type.equals("wechat.rollback")) authorization.require(username, "wechat.toggle");
        if (type.startsWith("wechat.")) return wechat.action(type, payload, username);
        if (type.equals("withdrawal.review")) return withdrawals.review(payload, username);
        if (!type.equals("merchant.audit") && !type.equals("rider.audit"))
            return business.action(type, payload, username);
        String id = String.valueOf(payload.get("id"));
        String decision = String.valueOf(payload.get("decision"));
        Object reasonValue = payload.get("reason");
        String reason = reasonValue == null ? "" : reasonValue.toString().trim();
        if ((!decision.equals("approve") && !decision.equals("reject")) ||
                (decision.equals("reject") && reason.isBlank()) || reason.length() > 500)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "审核决定或驳回原因无效");
        String result = decision.equals("approve") ? "已通过" : "已驳回";
        int changed = type.equals("merchant.audit")
                ? merchants.updatePendingAudit(result, reason.isBlank() ? null : reason, id)
                : riders.updatePendingAudit(result, reason.isBlank() ? null : reason, id);
        if (changed != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "记录不存在或已处理");
        db.insertAudit(username, type, id, "待审核", result, reason);
        return Map.of("ok", true);
    }
}

package com.campusfood.admin.service;

import com.campusfood.domain.BusinessStates.Audit;
import com.campusfood.domain.BusinessStates.AdminAudit;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import com.campusfood.admin.dao.AdminDao;
import com.campusfood.admin.dao.ConfigurationLockMapper;
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
    public static final Duration SESSION_LIFETIME = Duration.ofHours(48);

    private final AdminDao db;
    private final ConfigurationLockMapper configurationLocks;
    private final PasswordTransportService transport;
    private final MerchantMapper merchants;
    private final RiderMapper riders;
    private final StringRedisTemplate redis;
    private final BusinessService business;
    private final AuthorizationService authorization;
    private final WithdrawalService withdrawals;
    private final WechatService wechat;
    private final PrintingSettingsService printing;
    private final MerchantPrinterSettingsService merchantPrinters;
    private final PricingConfigurationService pricing;
    private final MerchantManagementService merchantManagement;
    private final RiderManagementService riderManagement;
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder();
    private final SecureRandom random = new SecureRandom();
    @Value("${app.redis-key-prefix:}")
    private String redisKeyPrefix;

    public AdminService(AdminDao db, StringRedisTemplate redis, BusinessService business,
                        AuthorizationService authorization, WithdrawalService withdrawals, WechatService wechat,
                        PasswordTransportService transport, MerchantMapper merchants, RiderMapper riders,
                        ConfigurationLockMapper configurationLocks, PrintingSettingsService printing,
                        MerchantPrinterSettingsService merchantPrinters, PricingConfigurationService pricing, MerchantManagementService merchantManagement,
                        RiderManagementService riderManagement) {
        this.db = db;
        this.configurationLocks = configurationLocks;
        this.transport = transport;
        this.merchants = merchants;
        this.riders = riders;
        this.redis = redis;
        this.business = business;
        this.authorization = authorization;
        this.withdrawals = withdrawals;
        this.wechat = wechat;
        this.printing = printing;
        this.merchantPrinters = merchantPrinters;
        this.pricing = pricing;
        this.merchantManagement = merchantManagement;
        this.riderManagement = riderManagement;
    }

    private String actor(String authHeader) {
        return actor(authHeader, false);
    }

    private String actor(String authHeader, boolean changingAccess) {
        if (authHeader == null || !authHeader.startsWith("Bearer "))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请登录");
        // Redis 保存用户名和会话版本；每次请求还需校验数据库中的最新版本。
        String session = redis.opsForValue().get(redisKeyPrefix + "admin:session:" + authHeader.substring(7));
        if (session == null || !session.contains("\n"))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已失效");
        // 先读取会话，再在配置锁内检查最新状态，避免未登录请求占用配置锁。
        if (changingAccess) configurationLocks.lock("admin");
        int separator = session.lastIndexOf('\n');
        String username = session.substring(0, separator), version = session.substring(separator + 1);
        String canonicalName = db.findSessionUsername(username, version);
        if (canonicalName == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已失效");
        return canonicalName;
    }

    /** 校验管理端会话及指定权限，供其他管理服务复用。 */
    public String requirePermission(String authHeader, String permission) {
        String username = actor(authHeader);
        authorization.require(username, permission);
        return username;
    }

    String requirePermissionForAccessChange(String authHeader, String permission) {
        String username = actor(authHeader, true);
        authorization.require(username, permission);
        return username;
    }

    public Map<String, Object> login(EncryptedPasswordRequest encrypted) {
        var body = transport.decrypt(encrypted, "/auth/login", LoginRequest.class);
        String username = valueOrEmpty(body.username());
        List<Map<String, Object>> users = db.findEnabledCredentials(username);
        if (users.isEmpty() || utf8Length(valueOrEmpty(body.password())) > 72 ||
                !passwords.matches(valueOrEmpty(body.password()), users.get(0).get("password_hash").toString()))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "账号或密码错误");
        username = (String) users.get(0).get("username");
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        redis.opsForValue().set(redisKeyPrefix + "admin:session:" + token, username + "\n" + users.get(0).get("session_version"),
                SESSION_LIFETIME);
        return Map.of("token", token, "user", sessionUser(username));
    }

    public Map<String, Object> session(String authHeader) {
        return sessionUser(actor(authHeader));
    }

    private Map<String, Object> sessionUser(String username) {
        return Map.of("name", username, "role", String.join("、", authorization.roles(username)),
                "permissions", authorization.permissions(username));
    }

    public Map<String, Object> logout(String authHeader) {
        actor(authHeader);
        redis.delete(redisKeyPrefix + "admin:session:" + authHeader.substring(7));
        return Map.of("ok", true);
    }

    private static String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }

    static void validatePassword(String password) {
        if (password.length() < 12 || utf8Length(password) > 72)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "密码至少 12 个字符，UTF-8 编码不超过 72 字节");
    }

    private static int utf8Length(String password) {
        return password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    }

    @Transactional
    public Map<String, Object> changePassword(EncryptedPasswordRequest encrypted, String authHeader) {
        String username = actor(authHeader, true);
        var body = transport.decrypt(encrypted, "/auth/password", ChangePasswordRequest.class);
        String oldPassword = valueOrEmpty(body.oldPassword());
        String newPassword = valueOrEmpty(body.newPassword());
        validatePassword(newPassword);
        String hash = db.lockPasswordHash(username);
        if (hash == null || utf8Length(oldPassword) > 72 || !passwords.matches(oldPassword, hash))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "原密码错误");
        db.changePasswordByUsername(passwords.encode(newPassword), username);
        db.insertSimpleAudit(username, "admin.password", username, AdminAudit.UPDATED.name());
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
        if (section.equals("withdrawals")) return business.withdrawals(db.hasBindingColumn("merchant_transfer_channel", "recipient_open_id"));
        if (section.equals("config")) {
            var result = new java.util.LinkedHashMap<>(business.workspace(section));
            result.put("printing", printing.current());
            return result;
        }
        Object orders =
                authorization.permissions(username).contains("order.view") ? business.workspace("orders").get("rows") :
                        List.of();
        if (section.equals("merchants")) return Map.of("rows", db.listMerchants(db.hasBindingColumn("merchant_identity", "merchant_id")), "orders", orders);
        if (section.equals("riders")) return Map.of("rows", db.listRiders(db.hasBindingColumn("rider_identity", "rider_id")), "orders", orders);
        return business.workspace(section);
    }

    public Map<String, Object> overview(String authHeader) {
        String username = actor(authHeader);
        authorization.require(username, "overview.view");
        return business.overview();
    }

    public Map<String, Object> merchantPhoto(String id, String authHeader) {
        authorization.require(actor(authHeader), "merchant.view");
        Map<String, Object> photo = id.startsWith("application:")
                ? merchantManagement.applicationPhoto(id.substring("application:".length()))
                : id.startsWith("revision:") ? merchants.revisionPhoto(id.substring("revision:".length()))
                : merchants.currentPhoto(id);
        if (photo == null || !(photo.get("image_data") instanceof byte[])) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "商户图片不存在");
        }
        return photo;
    }

    public Map<String, Object> riderStudentCard(String id, String authHeader) {
        authorization.require(actor(authHeader), "rider.view");
        var photo = riders.studentCard(id);
        if (photo == null || !(photo.get("image_data") instanceof byte[])) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "学生证照片不存在");
        }
        return photo;
    }

    public Map<String, Object> merchantMembers(String id, String authHeader) {
        String username = actor(authHeader);
        authorization.require(username, "merchant.view");
        authorization.require(username, "user.view");
        return Map.of("rows", merchantManagement.members(id));
    }

    public Map<String, Object> merchantPrinter(String id, String authHeader) {
        authorization.require(actor(authHeader), "merchant.printer.edit");
        return merchantPrinters.current(id);
    }

    public Map<String, Object> merchantPricing(String id, String authHeader) {
        authorization.require(actor(authHeader), "merchant.manage");
        return pricing.merchant(id);
    }

    public byte[] pickupQr(String id, String authHeader) {
        authorization.require(actor(authHeader), "order.view");
        return business.pickupQr(id);
    }

    public Map<String, Object> settlementSnapshots(String id, String authHeader) {
        authorization.require(actor(authHeader), "order.view");
        return Map.of("rows", pricing.snapshots(id));
    }

    @Transactional
    public Map<String, Object> action(AdminActionRequest body, String authHeader) {
        String username = actor(authHeader);
        String type = String.valueOf(body.type());
        String permission = switch (type) {
            case "merchant.audit" -> "merchant.audit";
            case "merchant.create", "merchant.bind", "merchant.unbind", "merchant.reorder", "merchant.update", "merchant.availability", "merchant.delete", "merchant.save-pricing" -> "merchant.manage";
            case "merchant.save-printer" -> "merchant.printer.edit";
            case "rider.create", "rider.bind", "rider.unbind", "rider.update", "rider.enable", "rider.disable" -> "rider.manage";
            case "rider.audit" -> "rider.audit";
            case "order.coordinate" -> "order.coordinate";
            case "refund.apply", "refund.offline", "refund.query" -> "refund.manage";
            case "withdrawal.review", "withdrawal.retry", "withdrawal.return-unidentified" -> "withdrawal.review";
            case "config.save", "config.save-printing" -> "config.edit";
            case "wechat.save-app", "wechat.save-payment", "wechat.rollback" -> "wechat.edit";
            default -> throw new ResponseStatusException(HttpStatus.NOT_FOUND, "操作不存在");
        };
        authorization.require(username, permission);
        if (!(body.payload() instanceof Map<?, ?> payload))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少审核信息");
        if ((permission.equals("merchant.manage") || permission.equals("rider.manage"))
            && (type.endsWith(".bind") || type.endsWith(".unbind") || !MiniProgramAccountService.userNumber(payload).isEmpty())) {
            authorization.require(username, "user.view");
        }
        // 回滚可能恢复旧版支付材料，继续要求额外的支付管理权限。
        if (type.equals("wechat.rollback")) authorization.require(username, "wechat.toggle");
        if (type.startsWith("wechat.")) return wechat.action(type, payload, username);
        if (type.equals("config.save-printing")) return printing.save(payload, username);
        if (type.equals("merchant.save-printer")) return merchantPrinters.save(payload, username);
        if (permission.equals("rider.manage")) return riderManagement.action(type, payload, username);
        if (type.equals("merchant.save-pricing")) return pricing.saveMerchant(payload, username);
        if (permission.equals("merchant.manage")) return merchantManagement.action(type, payload, username);
        if (type.equals("withdrawal.review")) return withdrawals.review(payload, username);
        if (type.equals("withdrawal.retry")) return withdrawals.retry(payload, username);
        if (type.equals("withdrawal.return-unidentified")) return withdrawals.returnUnidentifiedMerchantWithdrawal(payload, username);
        if (!type.equals("merchant.audit") && !type.equals("rider.audit"))
            return business.action(type, payload, username);
        String id = String.valueOf(payload.get("id"));
        String decision = String.valueOf(payload.get("decision"));
        Object reasonValue = payload.get("reason");
        String reason = reasonValue == null ? "" : reasonValue.toString().trim();
        if ((!decision.equals("approve") && !decision.equals("reject")) ||
                (decision.equals("reject") && reason.isBlank()) || reason.length() > 500)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "审核决定或驳回原因无效");
        Audit result = decision.equals("approve") ? Audit.APPROVED : Audit.REJECTED;
        if (type.equals("merchant.audit") && id.startsWith("application:")) {
            return merchantManagement.reviewApplication(id.substring("application:".length()), payload, result, reason, username);
        }
        int changed;
        if (type.equals("merchant.audit") && id.startsWith("revision:")) {
            String storeId = id.substring("revision:".length());
            if (merchants.lockStore(storeId) == null)
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "商户不存在");
            changed = merchants.reviewRevision(storeId, result, reason);
            if (changed == 1 && decision.equals("approve")) {
                merchants.applyRevision(storeId);
                merchants.applyPhotoRevision(storeId);
            }
        } else {
            changed = type.equals("merchant.audit")
                ? merchants.updatePendingAudit(result, reason.isBlank() ? null : reason, id)
                : riders.updatePendingAudit(result, reason.isBlank() ? null : reason, id);
        }
        if (changed != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "记录不存在或已处理");
        db.insertAudit(username, type, id, Audit.PENDING_REVIEW, result, reason);
        return Map.of("ok", true);
    }
}

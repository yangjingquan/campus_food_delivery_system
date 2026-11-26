package com.campusfood.admin.service;

import com.campusfood.admin.dao.BusinessDao;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MiniProgramAccountService {
    private final JdbcTemplate db;
    private final BusinessDao audit;
    private final String appId;

    public MiniProgramAccountService(JdbcTemplate db, BusinessDao audit,
                                    @Value("${app.wechat-app-id}") String appId) {
        this.db = db;
        this.audit = audit;
        this.appId = appId;
    }

    static String userNumber(Map<?, ?> payload) {
        Object value = payload.get("userNumber");
        String number = value == null ? "" : value.toString().strip();
        if (number.isEmpty()) return number;
        try {
            if (number.matches("[1-9][0-9]{0,18}") && Long.parseLong(number) > 0) return number;
        } catch (NumberFormatException ignored) {
            // A number larger than the profile primary key range cannot identify an account.
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "小程序用户编号无效");
    }

    static String newRiderIdentity() {
        return "R" + UUID.randomUUID().toString().replace("-", "").substring(0, 30);
    }

    long merchantIdentity(String userNumber) {
        String openId = openId(userNumber);
        db.update("""
            INSERT INTO merchant_identity(app_id,open_id) VALUES(?,?)
            ON DUPLICATE KEY UPDATE id=id
            """, appId, openId);
        return db.queryForObject("SELECT id FROM merchant_identity WHERE app_id=? AND open_id=? FOR UPDATE",
            Long.class, appId, openId);
    }

    String riderIdentity(String userNumber) {
        String openId = openId(userNumber);
        db.update("""
            INSERT INTO rider_identity(id,app_id,openid) VALUES(?,?,?)
            ON DUPLICATE KEY UPDATE id=id
            """, newRiderIdentity(), appId, openId);
        return db.queryForObject("SELECT id FROM rider_identity WHERE app_id=? AND openid=? FOR UPDATE",
            String.class, appId, openId);
    }

    private String openId(String userNumber) {
        var configured = db.queryForList("SELECT TRIM(user_app_id) FROM wechat_config ORDER BY id DESC LIMIT 1", String.class);
        if (!configured.isEmpty() && !configured.getFirst().isBlank() && !appId.equals(configured.getFirst())) {
            throw conflict("小程序 AppID 配置不一致，请核对部署配置后重试");
        }
        return db.query("SELECT openid FROM user_profile WHERE id=?", (row, n) -> row.getString(1),
            Long.parseLong(userNumber)).stream().findFirst().orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "小程序用户不存在，请让对方先登录用户端"));
    }

    String merchantProfile(long identityId) {
        var rows = db.queryForList("SELECT merchant_id FROM merchant_identity WHERE id=? FOR UPDATE",
            String.class, identityId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "商户微信身份不存在");
        return rows.getFirst();
    }

    String riderProfile(String identityId) {
        var rows = db.queryForList("SELECT rider_id FROM rider_identity WHERE id=? FOR UPDATE",
            String.class, identityId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "骑手微信身份不存在");
        return rows.getFirst();
    }

    void bindMerchant(long identityId, String merchantId, String actor) {
        String current = merchantProfile(identityId);
        if (merchantId.equals(current)) return;
        if (current != null) throw conflict("该微信已关联商户 " + current + "，请先处理原关联");
        try {
            db.update("UPDATE merchant_identity SET merchant_id=? WHERE id=?", merchantId, identityId);
        } catch (DuplicateKeyException exception) {
            throw conflict("该商户已关联其他微信账号，请先处理原关联");
        }
        audit.insertAudit(actor, "merchant.bind", merchantId, "", "ENABLED", "小程序商户身份 " + identityId);
    }

    void bindRider(String identityId, String riderId, String actor) {
        String current = riderProfile(identityId);
        if (riderId.equals(current)) return;
        if (current != null) throw conflict("该微信已关联骑手 " + current + "，请先处理原关联");
        try {
            db.update("UPDATE rider_identity SET rider_id=? WHERE id=?", riderId, identityId);
        } catch (DuplicateKeyException exception) {
            throw conflict("该骑手已关联其他微信账号，请先处理原关联");
        }
        audit.insertAudit(actor, "rider.bind", riderId, "", "ENABLED", "小程序骑手身份 " + identityId);
    }

    void requireSettledWithdrawals(String profileId) {
        var balances = db.queryForList("SELECT frozen_cents FROM finance_account WHERE id=? FOR UPDATE",
            Long.class, profileId);
        if (!balances.isEmpty() && balances.getFirst() > 0) {
            throw conflict("档案仍有冻结的提现资金，请处理后再解除关联");
        }
    }

    static String unbindReason(Map<?, ?> payload) {
        Object value = payload.get("reason");
        if (value instanceof String reason && !reason.isBlank() && reason.strip().length() <= 500) return reason.strip();
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请填写 1 至 500 字的解绑原因");
    }

    static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}

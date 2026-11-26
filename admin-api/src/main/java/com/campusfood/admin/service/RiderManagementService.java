package com.campusfood.admin.service;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.campusfood.admin.dao.BusinessDao;
import com.campusfood.admin.dao.FinanceAccountMapper;
import com.campusfood.admin.dao.RiderMapper;
import com.campusfood.admin.entity.FinanceAccount;
import com.campusfood.admin.entity.Rider;
import com.campusfood.domain.BusinessStates.Availability;
import com.campusfood.domain.BusinessStates.Gender;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RiderManagementService {
    private final RiderMapper riders;
    private final FinanceAccountMapper accounts;
    private final BusinessDao audit;

    private final MiniProgramAccountService identities;
    private final JdbcTemplate db;

    public RiderManagementService(RiderMapper riders, FinanceAccountMapper accounts, BusinessDao audit,
                                  MiniProgramAccountService identities, JdbcTemplate db) {
        this.riders = riders;
        this.accounts = accounts;
        this.audit = audit;
        this.identities = identities;
        this.db = db;
    }

    // 由管理操作入口的事务统一提交骑手档案、账务账户和审计记录。
    public Map<String, Object> action(String action, Map<?, ?> payload, String actor) {
        if ("rider.create".equals(action)) return create(payload, actor);

        String id = required(payload, "id", "骑手编号", 32);
        if ("rider.bind".equals(action)) return bind(id, payload, actor);
        if ("rider.unbind".equals(action)) return unbind(id, payload, actor);
        Rider current = riders.selectOne(Wrappers.<Rider>lambdaQuery().eq(Rider::getId, id).last("FOR UPDATE"));
        if (current == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "骑手不存在");

        var update = Wrappers.<Rider>lambdaUpdate().eq(Rider::getId, id);
        String before = Boolean.TRUE.equals(current.getEnabled()) ? "ENABLED" : "DISABLED";
        String after;
        if ("rider.update".equals(action)) {
            Fields fields = fields(payload, current);
            update.set(Rider::getName, fields.name()).set(Rider::getPhone, fields.phone())
                    .set(Rider::getGender, fields.gender()).set(Rider::getAvailability, fields.availability())
                    .set(Rider::getAvailableTime, fields.availableTime());
            accounts.update(null, Wrappers.<FinanceAccount>lambdaUpdate().set(FinanceAccount::getName, fields.name())
                    .eq(FinanceAccount::getId, id).eq(FinanceAccount::getType, "RIDER"));
            after = "UPDATED";
        } else {
            boolean enabled = "rider.enable".equals(action);
            update.set(Rider::getEnabled, enabled);
            after = enabled ? "ENABLED" : "DISABLED";
        }
        riders.update(null, update);
        audit.insertAudit(actor, action, id, before, after, "");
        return Map.of("ok", true, "id", id);
    }

    private Map<String, Object> create(Map<?, ?> payload, String actor) {
        Fields fields = fields(payload, null);
        String userNumber = MiniProgramAccountService.userNumber(payload);
        String identity = text(payload, "identityId");
        if (!userNumber.isEmpty()) {
            if (!identity.isEmpty()) throw invalid("请只选择一种骑手关联方式");
            identity = identities.riderIdentity(userNumber);
        } else if (!identity.isEmpty() && (!identity.matches("R[a-f0-9]{30}") || riders.lockIdentity(identity) == null)) {
            throw invalid("骑手小程序身份编号无效");
        }
        if (!identity.isEmpty() && identities.riderProfile(identity) != null) {
            throw MiniProgramAccountService.conflict("该微信已建立骑手档案，请在列表中编辑");
        }
        String id = identity.isEmpty() ? MiniProgramAccountService.newRiderIdentity() : identity;
        if (!identity.isEmpty() && riders.selectById(id) != null) {
            id = MiniProgramAccountService.newRiderIdentity();
        }
        String joinedAt = LocalDateTime.now(ZoneId.of("Asia/Shanghai"))
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        riders.insert(Rider.create(id, fields.name(), fields.phone(), fields.gender(), fields.availability(),
                fields.availableTime(), joinedAt));
        riders.createFinanceAccount(id, fields.name());
        if (!identity.isEmpty()) identities.bindRider(identity, id, actor);
        audit.insertAudit(actor, "rider.create", id, "", "ENABLED", "");
        return Map.of("ok", true, "id", id);
    }

    private Map<String, Object> bind(String id, Map<?, ?> payload, String actor) {
        String userNumber = MiniProgramAccountService.userNumber(payload);
        if (userNumber.isEmpty()) throw invalid("请选择小程序用户");
        String identity = identities.riderIdentity(userNumber);
        var rider = riders.selectOne(Wrappers.<Rider>lambdaQuery().eq(Rider::getId, id).last("FOR UPDATE"));
        if (rider == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "骑手不存在");
        if (!id.equals(identities.riderProfile(identity))) {
            identities.bindRider(identity, id, actor);
            db.update("""
                UPDATE rider SET audit_status='NOT_ENROLLED',audit_reason=NULL WHERE id=? AND NOT EXISTS
                    (SELECT 1 FROM rider_student_card WHERE rider_id=?)
                """, id, id);
        }
        return Map.of("ok", true, "id", id);
    }

    private Map<String, Object> unbind(String id, Map<?, ?> payload, String actor) {
        String reason = MiniProgramAccountService.unbindReason(payload);
        Object bindingId = payload.get("bindingId");
        if (!(bindingId instanceof String identity) || !identity.matches("R[a-f0-9]{30}")) {
            throw invalid("骑手小程序身份编号无效");
        }
        if (!id.equals(identities.riderProfile(identity))) {
            throw MiniProgramAccountService.conflict("关联账号已变化，请刷新后重试");
        }
        var rider = riders.selectOne(Wrappers.<Rider>lambdaQuery().eq(Rider::getId, id).last("FOR UPDATE"));
        if (rider == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "骑手不存在");
        if (!db.queryForList("""
            SELECT d.order_id FROM user_order_detail d JOIN business_order o ON o.id=d.order_id
            WHERE d.pickup_rider_id=? AND o.fulfill_status='DELIVERING' LIMIT 1 FOR SHARE
            """, String.class, id).isEmpty()) {
            throw MiniProgramAccountService.conflict("骑手仍有配送中的订单，请完成后再解除关联");
        }
        identities.requireSettledWithdrawals(id);
        db.update("UPDATE rider_identity SET rider_id=NULL WHERE id=?", identity);
        db.update("DELETE FROM rider_session WHERE rider_id=?", identity);
        db.update("DELETE FROM rider_reminder_subscription WHERE rider_id=?", identity);
        db.update("UPDATE rider SET audit_status='NOT_ENROLLED',audit_reason=NULL WHERE id=?", id);
        audit.insertAudit(actor, "rider.unbind", id, identity, "DISABLED", reason);
        return Map.of("ok", true, "id", id);
    }

    private static Fields fields(Map<?, ?> payload, Rider current) {
        String name = required(payload, "name", "姓名", 120);
        String phone = required(payload, "phone", "手机号", 32);
        if (!phone.matches("1[3-9]\\d{9}") && (current == null || !phone.equals(current.getPhone())))
            throw invalid("请输入有效的手机号");
        Gender gender = parse(Gender.class, text(payload, "gender"), "性别");
        Availability availability = parse(Availability.class, text(payload, "availability"), "可送餐时间");
        String time = availability == Availability.CUSTOM ? text(payload, "availableTime") : "";
        if (availability == Availability.CUSTOM && !validRange(time) && (current == null
                || current.getAvailability() != availability || !time.equals(current.getAvailableTime())))
            throw invalid("自定义时段须为开始时间早于结束时间的 HH:mm-HH:mm");
        return new Fields(name, phone, gender, availability, time);
    }

    private static boolean validRange(String time) {
        if (!time.matches("(?:[01]\\d|2[0-3]):[0-5]\\d-(?:[01]\\d|2[0-3]):[0-5]\\d")) return false;
        return LocalTime.parse(time.substring(0, 5)).isBefore(LocalTime.parse(time.substring(6)));
    }

    private static <T extends Enum<T>> T parse(Class<T> type, String value, String label) {
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException exception) {
            throw invalid(label + "无效");
        }
    }

    private static String required(Map<?, ?> payload, String key, String label, int maxLength) {
        String value = text(payload, key);
        if (value.isEmpty() || value.length() > maxLength) throw invalid(label + "必填且不能超过 " + maxLength + " 个字符");
        return value;
    }

    private static String text(Map<?, ?> payload, String key) {
        return payload.get(key) instanceof String value ? value.strip() : "";
    }

    private static ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private record Fields(String name, String phone, Gender gender, Availability availability, String availableTime) {}
}

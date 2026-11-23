package com.campusfood.admin.service;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.campusfood.admin.dao.BusinessDao;
import com.campusfood.admin.dao.FinanceAccountMapper;
import com.campusfood.admin.dao.RiderMapper;
import com.campusfood.admin.entity.FinanceAccount;
import com.campusfood.admin.entity.Rider;
import com.campusfood.domain.BusinessStates.Availability;
import com.campusfood.domain.BusinessStates.Gender;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RiderManagementService {
    private final RiderMapper riders;
    private final FinanceAccountMapper accounts;
    private final BusinessDao audit;

    public RiderManagementService(RiderMapper riders, FinanceAccountMapper accounts, BusinessDao audit) {
        this.riders = riders;
        this.accounts = accounts;
        this.audit = audit;
    }

    // 由管理操作入口的事务统一提交骑手档案、账务账户和审计记录。
    public Map<String, Object> action(String action, Map<?, ?> payload, String actor) {
        if ("rider.create".equals(action)) return create(payload, actor);

        String id = required(payload, "id", "骑手编号", 32);
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
        String id = text(payload, "identityId");
        if (id.isEmpty()) {
            id = "R" + UUID.randomUUID().toString().replace("-", "").substring(0, 30);
        } else {
            if (!id.matches("R[a-f0-9]{30}") || riders.lockIdentity(id) == null)
                throw invalid("骑手小程序身份编号无效");
            if (riders.selectById(id) != null)
                throw new ResponseStatusException(HttpStatus.CONFLICT, "该骑手已建立档案，请在列表中编辑");
        }
        String joinedAt = LocalDateTime.now(ZoneId.of("Asia/Shanghai"))
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        riders.insert(Rider.create(id, fields.name(), fields.phone(), fields.gender(), fields.availability(),
                fields.availableTime(), joinedAt));
        riders.createFinanceAccount(id, fields.name());
        audit.insertAudit(actor, "rider.create", id, "", "ENABLED", "");
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

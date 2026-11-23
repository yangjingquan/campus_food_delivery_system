package com.campusfood.admin.service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.campusfood.admin.dao.BusinessDao;
import com.campusfood.admin.dao.FinanceAccountMapper;
import com.campusfood.admin.dao.MerchantMapper;
import com.campusfood.admin.entity.FinanceAccount;
import com.campusfood.admin.entity.Merchant;
import com.campusfood.domain.BusinessStates.Audit;
import com.campusfood.domain.BusinessStates.Business;
import com.campusfood.domain.InlinePhoto;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** 管理员维护商户档案和用户端展示状态。 */
@Service
public class MerchantManagementService {
    private static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter JOIN_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final MerchantMapper merchants;
    private final FinanceAccountMapper accounts;
    private final BusinessDao audit;

    public MerchantManagementService(MerchantMapper merchants, FinanceAccountMapper accounts, BusinessDao audit) {
        this.merchants = merchants;
        this.accounts = accounts;
        this.audit = audit;
    }

    @Transactional
    public Map<String, Object> action(String action, Map<?, ?> payload, String actor) {
        if ("merchant.create".equals(action)) {
            return create(payload, actor);
        }
        String id = text(payload, "id", "商户编号", 32);
        Merchant current = merchants.selectOne(Wrappers.<Merchant>lambdaQuery()
                .eq(Merchant::getId, id).isNull(Merchant::getDeletedAt).last("FOR UPDATE"));
        if (current == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "商户不存在");

        var update = Wrappers.<Merchant>lambdaUpdate().eq(Merchant::getId, id);
        String before = Boolean.TRUE.equals(current.getListed()) ? "ENABLED" : "DISABLED";
        String after;
        switch (action) {
            case "merchant.update" -> {
                if (merchants.lockPendingRevision(id) != null)
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "请先处理该商户的待审资料");
                StoreFields fields = fields(payload, current.getPhone());
                savePhoto(id, readPhoto(payload));
                update.set(Merchant::getName, fields.name()).set(Merchant::getContact, fields.contact())
                        .set(Merchant::getPhone, fields.phone()).set(Merchant::getAddress, fields.address())
                        .set(current.getAuditStatus() == Audit.APPROVED, Merchant::getBusinessStatus, fields.business());
                accounts.update(null, Wrappers.<FinanceAccount>lambdaUpdate().set(FinanceAccount::getName, fields.name())
                        .eq(FinanceAccount::getId, id).eq(FinanceAccount::getType, "MERCHANT"));
                after = "UPDATED";
            }
            case "merchant.availability" -> {
                boolean listed = flag(payload, "listed", "上下架状态");
                if (listed && current.getAuditStatus() != Audit.APPROVED)
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "商户审核通过后才能上架");
                update.set(Merchant::getListed, listed);
                after = listed ? "ENABLED" : "DISABLED";
            }
            case "merchant.delete" -> {
                update.set(Merchant::getDeletedAt, LocalDateTime.now(ZoneId.of("UTC")))
                        .set(Merchant::getListed, false).set(Merchant::getBusinessStatus, Business.STORE_CLOSED);
                after = "DISABLED";
            }
            default -> throw new ResponseStatusException(HttpStatus.NOT_FOUND, "操作不存在");
        }
        merchants.update(null, update);
        audit.insertAudit(actor, action, id, before, after, "");
        return Map.of("ok", true, "id", id);
    }

    private Map<String, Object> create(Map<?, ?> payload, String actor) {
        StoreFields fields = fields(payload, null);
        InlinePhoto photo = readPhoto(payload);
        String id = UUID.randomUUID().toString().replace("-", "");
        merchants.insert(Merchant.create(id, fields.name(), fields.contact(), fields.phone(), fields.address(),
                fields.business(), LocalDateTime.now(BEIJING).format(JOIN_TIME)));
        savePhoto(id, photo);
        merchants.createFinanceAccount(id, fields.name());
        audit.insertAudit(actor, "merchant.create", id, "", "DISABLED", "");
        return Map.of("ok", true, "id", id);
    }

    private static InlinePhoto readPhoto(Map<?, ?> payload) {
        Object image = payload.get("image");
        if (image == null) return null;
        if (!(image instanceof String value)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "商户图片格式无效");
        }
        try {
            return InlinePhoto.parse(value.trim(), "商户图片");
        } catch (IllegalArgumentException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, error.getMessage());
        }
    }

    private void savePhoto(String id, InlinePhoto photo) {
        if (photo != null) merchants.savePhoto(id, photo.data(), photo.mediaType());
    }

    private record StoreFields(String name, String contact, String phone, String address, Business business) {}

    private static StoreFields fields(Map<?, ?> payload, String oldPhone) {
        String phone = text(payload, "phone", "联系电话", 32);
        if (!phone.equals(oldPhone) && (!phone.matches("[0-9+() -]{7,20}")
                || phone.replaceAll("[^0-9]", "").length() < 7)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请填写有效的联系电话");
        }
        return new StoreFields(text(payload, "name", "商户名称", 120), text(payload, "contact", "联系人", 80),
                phone, text(payload, "address", "店铺地址", 255),
                flag(payload, "open", "营业状态") ? Business.STORE_OPEN : Business.STORE_CLOSED);
    }

    private static String text(Map<?, ?> values, String key, String label, int maxLength) {
        if (values.get(key) instanceof String text && !text.isBlank() && text.strip().length() <= maxLength) {
            return text.strip();
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + "必填且不能超过 " + maxLength + " 个字符");
    }

    private static boolean flag(Map<?, ?> values, String key, String label) {
        if (values.get(key) instanceof Boolean value) return value;
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + "无效");
    }
}

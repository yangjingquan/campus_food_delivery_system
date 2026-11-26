package com.campusfood.admin.service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;

import com.campusfood.domain.DisplayOrder;
import java.util.UUID;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.campusfood.admin.dao.AdminDao;
import com.campusfood.admin.dao.BusinessDao;
import com.campusfood.admin.dao.ConfigurationLockMapper;
import com.campusfood.admin.dao.FinanceAccountMapper;
import com.campusfood.admin.dao.MerchantMapper;
import com.campusfood.admin.entity.FinanceAccount;
import com.campusfood.admin.entity.Merchant;
import com.campusfood.domain.BusinessStates.Audit;
import com.campusfood.domain.BusinessStates.Business;
import com.campusfood.domain.InlinePhoto;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
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

    private final ConfigurationLockMapper locks;
    private final MiniProgramAccountService identities;
    private final JdbcTemplate db;
    private final AdminDao admin;

    public MerchantManagementService(MerchantMapper merchants, FinanceAccountMapper accounts, BusinessDao audit,
                                     ConfigurationLockMapper locks, MiniProgramAccountService identities, JdbcTemplate db,
                                     AdminDao admin) {
        this.merchants = merchants;
        this.accounts = accounts;
        this.audit = audit;
        this.locks = locks;
        this.identities = identities;
        this.db = db;
        this.admin = admin;
    }

    @Transactional
    public Map<String, Object> action(String action, Map<?, ?> payload, String actor) {
        if ("merchant.create".equals(action)) {
            return create(payload, actor);
        }
        if ("merchant.reorder".equals(action)) {
            reorder(payload, actor);
            return Map.of("ok", true);
        }
        String id = text(payload, "id", "商户编号", 32);
        if ("merchant.bind".equals(action)) return bind(id, payload, actor);
        if ("merchant.unbind".equals(action)) return unbind(id, payload, actor);
        if ("merchant.update".equals(action) || "merchant.delete".equals(action)) locks.lock("merchant_enrollment");
        if ("merchant.delete".equals(action) && admin.hasBindingColumn("merchant_identity", "merchant_id")
                && !db.queryForList("SELECT id FROM merchant_identity WHERE merchant_id=? FOR UPDATE", Long.class, id).isEmpty()) {
            throw MiniProgramAccountService.conflict("请先解除小程序账号关联，再移除商户");
        }
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

    private void reorder(Map<?, ?> payload, String actor) {
        DisplayOrder order;
        try {
            order = DisplayOrder.from(payload.get("previousIds"), payload.get("ids"), 32);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
        var stores = merchants.selectList(Wrappers.<Merchant>lambdaQuery()
                .select(Merchant::getId, Merchant::getDisplayOrder).isNull(Merchant::getDeletedAt)
                .orderByAsc(Merchant::getDisplayOrder, Merchant::getId).last("FOR UPDATE"));
        var current = stores.stream().map(Merchant::getId).toList();
        var requested = order.applyTo(current);
        if (requested == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "商户列表或顺序已变化，请刷新后再调整");
        }
        if (current.equals(requested)) return;

        var ranks = new java.util.HashMap<String, Integer>();
        for (int i = 0; i < requested.size(); i++) ranks.put(requested.get(i), i);
        for (Merchant store : stores) {
            int rank = ranks.get(store.getId());
            if (store.getDisplayOrder() == rank) continue;
            merchants.update(null, Wrappers.<Merchant>lambdaUpdate().eq(Merchant::getId, store.getId())
                    .set(Merchant::getDisplayOrder, rank));
        }
        audit.insertAudit(actor, "merchant.reorder", "merchants", "", "UPDATED", "");
    }

    private Map<String, Object> create(Map<?, ?> payload, String actor) {
        StoreFields fields = fields(payload, null);
        InlinePhoto photo = readPhoto(payload);
        String userNumber = MiniProgramAccountService.userNumber(payload);
        locks.lock("merchant_enrollment");
        Long identity = userNumber.isEmpty() ? null : identityForDirectBinding(userNumber);
        if (identity != null) {
            String current = identities.merchantProfile(identity);
            if (current != null) throw MiniProgramAccountService.conflict("该微信已关联商户 " + current + "，请在列表中处理");
        }
        requireNewStore(fields);
        if (Boolean.TRUE.equals(db.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM merchant_enrollment_request WHERE status='PENDING_REVIEW'
                AND REPLACE(TRIM(name),' ','')=REPLACE(TRIM(?),' ','')
                AND REPLACE(TRIM(address),' ','')=REPLACE(TRIM(?),' ',''))
            """, Boolean.class, fields.name(), fields.address()))) {
            throw MiniProgramAccountService.conflict("相同店名和地址已有待审入驻申请，请从申请完成建档或关联");
        }
        String id = insertStore(fields, actor);
        savePhoto(id, photo);
        if (identity != null) identities.bindMerchant(identity, id, actor);
        return Map.of("ok", true, "id", id);
    }

    private String insertStore(StoreFields fields, String actor) {
        String id = UUID.randomUUID().toString().replace("-", "");
        merchants.insert(Merchant.create(id, fields.name(), fields.contact(), fields.phone(), fields.address(),
                fields.business(), LocalDateTime.now(BEIJING).format(JOIN_TIME)));
        merchants.createFinanceAccount(id, fields.name());
        audit.insertAudit(actor, "merchant.create", id, "", "DISABLED", "");
        return id;
    }

    private void requireNewStore(StoreFields fields) {
        var existing = db.queryForList("""
            SELECT id FROM merchant WHERE deleted_at IS NULL
                AND REPLACE(TRIM(name),' ','')=REPLACE(TRIM(?),' ','')
                AND REPLACE(TRIM(address),' ','')=REPLACE(TRIM(?),' ','')
            ORDER BY id LIMIT 1 FOR UPDATE
            """, String.class, fields.name(), fields.address());
        if (!existing.isEmpty()) {
            throw MiniProgramAccountService.conflict("相同店名和地址已存在商户 " + existing.getFirst() + "，请关联已有档案");
        }
    }

    private long identityForDirectBinding(String userNumber) {
        if (userNumber.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择小程序用户");
        long identity = identities.merchantIdentity(userNumber);
        var status = db.queryForList("""
            SELECT status FROM merchant_enrollment_request WHERE identity_id=? FOR UPDATE
            """, String.class, identity);
        if (!status.isEmpty() && Audit.PENDING_REVIEW.is(status.getFirst())) {
            throw MiniProgramAccountService.conflict("该账号有待审入驻申请，请从申请关联商户");
        }
        return identity;
    }

    private Map<String, Object> bind(String id, Map<?, ?> payload, String actor) {
        locks.lock("merchant_enrollment");
        long identity = identityForDirectBinding(MiniProgramAccountService.userNumber(payload));
        bind(identity, id, actor);
        return Map.of("ok", true, "id", id);
    }

    private void bind(long identity, String id, String actor) {
        if (merchants.lockStore(id) == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "商户不存在");
        identities.bindMerchant(identity, id, actor);
    }

    private Map<String, Object> unbind(String id, Map<?, ?> payload, String actor) {
        String reason = MiniProgramAccountService.unbindReason(payload);
        locks.lock("merchant_enrollment");
        var bound = db.queryForList("SELECT id FROM merchant_identity WHERE merchant_id=? FOR UPDATE", Long.class, id);
        if (bound.isEmpty()) return Map.of("ok", true, "id", id);
        long identity = bound.getFirst();
        if (!Long.toString(identity).equals(String.valueOf(payload.get("bindingId")))) {
            throw MiniProgramAccountService.conflict("关联账号已变化，请刷新后重试");
        }
        identities.requireSettledWithdrawals(id);
        if (merchants.lockPendingRevision(id) != null) {
            throw MiniProgramAccountService.conflict("请先处理该商户的待审资料");
        }
        db.update("UPDATE merchant_identity SET merchant_id=NULL WHERE id=?", identity);
        db.update("DELETE FROM merchant_session WHERE identity_id=?", identity);
        audit.insertAudit(actor, "merchant.unbind", id, Long.toString(identity), "DISABLED", reason);
        return Map.of("ok", true, "id", id);
    }

    Map<String, Object> reviewApplication(String applicationId, Map<?, ?> payload, Audit result,
                                          String reason, String actor) {
        long identityId;
        try {
            identityId = Long.parseLong(applicationId);
        } catch (NumberFormatException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "入驻申请编号无效");
        }
        locks.lock("merchant_enrollment");
        String current = identities.merchantProfile(identityId);
        var requests = db.queryForList("""
            SELECT name,contact,phone,address,status,merchant_id
            FROM merchant_enrollment_request WHERE identity_id=? FOR UPDATE
            """, identityId);
        if (requests.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "入驻申请不存在");
        var application = requests.getFirst();
        if (!Audit.PENDING_REVIEW.is(application.get("status"))) {
            throw MiniProgramAccountService.conflict("入驻申请已处理，请刷新列表");
        }
        String id = null;
        if (result == Audit.APPROVED) {
            if (current != null) throw MiniProgramAccountService.conflict("该微信已关联商户 " + current + "，请先处理原关联");
            String choice = String.valueOf(payload.get("storeChoice"));
            if ("create".equals(choice)) {
                var fields = new StoreFields((String) application.get("name"), (String) application.get("contact"),
                    (String) application.get("phone"), (String) application.get("address"), Business.STORE_CLOSED);
                requireNewStore(fields);
                id = insertStore(fields, actor);
                db.update("""
                    INSERT INTO merchant_store_photo(merchant_id,image_data,media_type)
                    SELECT ?,image_data,media_type FROM merchant_enrollment_request WHERE identity_id=?
                    """, id, identityId);
            } else if ("existing".equals(choice)) {
                id = text(payload, "merchantId", "商户编号", 32);
                String status = db.query("SELECT audit_status FROM merchant WHERE id=? AND deleted_at IS NULL FOR UPDATE",
                    (row, n) -> row.getString(1), id).stream().findFirst().orElse(null);
                if (!Audit.APPROVED.is(status)) {
                    throw MiniProgramAccountService.conflict("请选择已通过审核的商户档案");
                }
            } else {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择关联已有商户或确认新店建档");
            }
            bind(identityId, id, actor);
        }
        db.update("UPDATE merchant_enrollment_request SET status=?,reason=?,merchant_id=? WHERE identity_id=?",
            result.name(), reason, id, identityId);
        audit.insertAudit(actor, "merchant.audit", "application:" + identityId,
            Audit.PENDING_REVIEW.name(), result.name(), reason);
        return id == null ? Map.of("ok", true) : Map.of("ok", true, "id", id);
    }

    public Map<String, Object> applicationPhoto(String applicationId) {
        if (!applicationId.matches("[0-9]{1,19}")) return Map.of();
        return db.queryForList("""
            SELECT image_data,media_type FROM merchant_enrollment_request
            WHERE CAST(identity_id AS CHAR)=? AND status='PENDING_REVIEW'
            """, applicationId).stream().findFirst().orElse(Map.of());
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

package com.campusfood.merchant.service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class StoreService {
    private final JdbcTemplate db;

    public StoreService(JdbcTemplate db) {
        this.db = db;
    }

    public Map<String, Object> profile(SessionService.Identity identity) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("store", null);
        result.put("revision", null);
        if (identity.merchantId() != null) {
            result.put("store", db.queryForMap("""
                SELECT m.id,m.name,m.contact,m.phone,m.address,m.audit_status AS auditStatus,
                       m.audit_reason AS auditReason,m.business_status AS businessStatus,
                       m.minimum_order_cents AS minimumOrderCents,
                       EXISTS(SELECT 1 FROM merchant_store_photo WHERE merchant_id=m.id) AS hasPhoto
                FROM merchant m WHERE m.id=?
                """, identity.merchantId()));
            db.queryForList("""
                SELECT name,contact,phone,address,status,reason,
                       image_data IS NOT NULL AS hasPhoto
                FROM merchant_profile_revision WHERE merchant_id=?
                """, identity.merchantId()).stream().findFirst().ifPresent(row -> result.put("revision", row));
        }
        return result;
    }

    @Transactional
    public void submit(SessionService.Identity identity, Map<String, Object> body) {
        String name = Inputs.text(body, "name", "店铺名称", 120, true);
        String contact = Inputs.text(body, "contact", "联系人", 80, true);
        String phone = Inputs.text(body, "phone", "联系电话", 20, true);
        String address = Inputs.text(body, "address", "店铺地址", 255, true);
        if (!phone.matches("[0-9+() -]{7,20}") || phone.replaceAll("[^0-9]", "").length() < 7) {
            throw Inputs.invalid("请填写有效的联系电话");
        }
        UploadedPhoto photo = UploadedPhoto.from(body, "店铺图片");
        String id = db.queryForMap("SELECT merchant_id FROM merchant_identity WHERE id=? FOR UPDATE", identity.id())
            .get("merchant_id") instanceof String value ? value : null;
        if (id == null) {
            if (photo == null) throw Inputs.invalid("请上传店铺图片");
            id = UUID.randomUUID().toString().replace("-", "");
            String now = LocalDateTime.now(ZoneId.of("Asia/Shanghai"))
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            db.update("""
                INSERT INTO merchant(id,name,contact,phone,address,audit_status,business_status,joined_at)
                VALUES(?,?,?,?,?,'待审核','休息中',?)
                """, id, name, contact, phone, address, now);
            db.update("UPDATE merchant_identity SET merchant_id=? WHERE id=?", id, identity.id());
            db.update("INSERT INTO finance_account(id,name,type,balance_cents,status) VALUES(?,?,'商户',0,'正常')", id, name);
            savePhoto(id, photo);
            return;
        }

        String status = db.queryForObject("SELECT audit_status FROM merchant WHERE id=? FOR UPDATE", String.class, id);
        if ("已驳回".equals(status)) {
            db.update("""
                UPDATE merchant SET name=?,contact=?,phone=?,address=?,audit_status='待审核',audit_reason=NULL
                WHERE id=?
                """, name, contact, phone, address, id);
            if (photo != null) savePhoto(id, photo);
            db.update("UPDATE finance_account SET name=? WHERE id=? AND type='商户'", name, id);
            return;
        }
        if (!"已通过".equals(status)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "入驻资料正在审核，请等待结果");
        }

        // 待审资料独立保存，审核期间继续使用当前营业档案。
        String revision = db.query("SELECT status FROM merchant_profile_revision WHERE merchant_id=?",
            (row, n) -> row.getString(1), id).stream().findFirst().orElse("");
        if ("待审核".equals(revision)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "资料变更正在审核，请等待结果");
        }
        db.update("DELETE FROM merchant_profile_revision WHERE merchant_id=?", id);
        db.update("""
            INSERT INTO merchant_profile_revision(merchant_id,name,contact,phone,address,image_data,media_type,status)
            VALUES(?,?,?,?,?,?,?,'待审核')
            """, id, name, contact, phone, address, photo == null ? null : photo.data(),
            photo == null ? null : photo.mediaType());
    }

    public UploadedPhoto storePhoto(SessionService.Identity identity) {
        return db.query("SELECT image_data,media_type FROM merchant_store_photo WHERE merchant_id=?",
            (row, n) -> new UploadedPhoto(row.getBytes(1), row.getString(2)), identity.merchantId())
            .stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "店铺图片不存在"));
    }

    public void businessStatus(String id, Map<String, Object> body) {
        db.update("UPDATE merchant SET business_status=? WHERE id=? AND audit_status='已通过'",
            Inputs.flag(body, "open", "营业状态") ? "营业中" : "休息中", id);
    }

    public void minimumOrder(String id, Map<String, Object> body) {
        long cents = Inputs.integer(body, "minimumOrderCents", "起送价（分）", 0, 99_999_999_999L);
        db.update("UPDATE merchant SET minimum_order_cents=? WHERE id=? AND audit_status='已通过'", cents, id);
    }

    private void savePhoto(String id, UploadedPhoto photo) {
        db.update("""
            INSERT INTO merchant_store_photo(merchant_id,image_data,media_type) VALUES(?,?,?)
            ON DUPLICATE KEY UPDATE image_data=?,media_type=?
            """, id, photo.data(), photo.mediaType(), photo.data(), photo.mediaType());
    }

}

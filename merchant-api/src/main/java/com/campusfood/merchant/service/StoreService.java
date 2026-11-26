package com.campusfood.merchant.service;

import com.campusfood.domain.BusinessStates.Audit;
import com.campusfood.domain.BusinessStates.Business;

import java.util.LinkedHashMap;
import java.util.Map;

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
        result.put("application", null);
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
        } else {
            db.queryForList("""
                SELECT name,contact,phone,address,status,reason
                FROM merchant_enrollment_request WHERE identity_id=? AND status IN ('PENDING_REVIEW','REJECTED')
                """, identity.id()).stream().findFirst().ifPresent(row -> result.put("application", row));
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
            var statuses = db.queryForList("""
                SELECT status FROM merchant_enrollment_request WHERE identity_id=? FOR UPDATE
                """, String.class, identity.id());
            if (!statuses.isEmpty() && Audit.PENDING_REVIEW.is(statuses.getFirst())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "入驻资料正在审核，请等待结果");
            }
            if (photo == null && (statuses.isEmpty() || !Audit.REJECTED.is(statuses.getFirst()))) {
                throw Inputs.invalid("请上传店铺图片");
            }
            if (photo != null) {
                db.update("""
                    INSERT INTO merchant_enrollment_request(identity_id,name,contact,phone,address,image_data,media_type,status)
                    VALUES(?,?,?,?,?,?,?,'PENDING_REVIEW') AS incoming
                    ON DUPLICATE KEY UPDATE name=incoming.name,contact=incoming.contact,phone=incoming.phone,
                        address=incoming.address,image_data=incoming.image_data,media_type=incoming.media_type,
                        status='PENDING_REVIEW',reason=NULL,merchant_id=NULL,submitted_at=CURRENT_TIMESTAMP
                    """, identity.id(), name, contact, phone, address, photo.data(), photo.mediaType());
            } else {
                db.update("""
                    UPDATE merchant_enrollment_request SET name=?,contact=?,phone=?,address=?,status='PENDING_REVIEW',
                        reason=NULL,merchant_id=NULL,submitted_at=CURRENT_TIMESTAMP WHERE identity_id=?
                    """, name, contact, phone, address, identity.id());
            }
            return;
        }

        String status = db.query("SELECT audit_status FROM merchant WHERE id=? AND deleted_at IS NULL FOR UPDATE",
            (row, n) -> row.getString(1), id).stream().findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "商户档案已删除"));
        if (Audit.REJECTED.is(status)) {
            db.update("""
                UPDATE merchant SET name=?,contact=?,phone=?,address=?,audit_status='PENDING_REVIEW',audit_reason=NULL
                WHERE id=?
                """, name, contact, phone, address, id);
            if (photo != null) savePhoto(id, photo);
            db.update("UPDATE finance_account SET name=? WHERE id=? AND type='MERCHANT'", name, id);
            return;
        }
        if (!Audit.APPROVED.is(status)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "入驻资料正在审核，请等待结果");
        }

        // 待审资料独立保存，审核期间继续使用当前营业档案。
        String revision = db.query("SELECT status FROM merchant_profile_revision WHERE merchant_id=?",
            (row, n) -> row.getString(1), id).stream().findFirst().orElse("");
        if (Audit.PENDING_REVIEW.is(revision)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "资料变更正在审核，请等待结果");
        }
        db.update("DELETE FROM merchant_profile_revision WHERE merchant_id=?", id);
        db.update("""
            INSERT INTO merchant_profile_revision(merchant_id,name,contact,phone,address,image_data,media_type,status)
            VALUES(?,?,?,?,?,?,?,'PENDING_REVIEW')
            """, id, name, contact, phone, address, photo == null ? null : photo.data(),
            photo == null ? null : photo.mediaType());
    }

    public UploadedPhoto storePhoto(SessionService.Identity identity) {
        String sql = identity.merchantId() == null
            ? "SELECT image_data,media_type FROM merchant_enrollment_request WHERE identity_id=? AND status IN ('PENDING_REVIEW','REJECTED')"
            : "SELECT image_data,media_type FROM merchant_store_photo WHERE merchant_id=?";
        Object id = identity.merchantId() == null ? identity.id() : identity.merchantId();
        return db.query(sql, (row, n) -> new UploadedPhoto(row.getBytes(1), row.getString(2)), id)
            .stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "店铺图片不存在"));
    }

    public void businessStatus(String id, Map<String, Object> body) {
        db.update("UPDATE merchant SET business_status=? WHERE id=? AND audit_status='APPROVED' AND deleted_at IS NULL",
            Inputs.flag(body, "open", "营业状态") ? Business.STORE_OPEN.name() : Business.STORE_CLOSED.name(), id);
    }

    public void minimumOrder(String id, Map<String, Object> body) {
        long cents = Inputs.integer(body, "minimumOrderCents", "起送价（分）", 0, 99_999_999_999L);
        db.update("UPDATE merchant SET minimum_order_cents=? WHERE id=? AND audit_status='APPROVED' AND deleted_at IS NULL", cents, id);
    }

    private void savePhoto(String id, UploadedPhoto photo) {
        db.update("""
            INSERT INTO merchant_store_photo(merchant_id,image_data,media_type) VALUES(?,?,?)
            ON DUPLICATE KEY UPDATE image_data=?,media_type=?
            """, id, photo.data(), photo.mediaType(), photo.data(), photo.mediaType());
    }

}

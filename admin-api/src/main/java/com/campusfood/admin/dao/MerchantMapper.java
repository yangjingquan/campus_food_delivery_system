package com.campusfood.admin.dao;

import java.util.Map;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.campusfood.admin.entity.Merchant;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 商户审核及待办统计。 */
@Mapper
public interface MerchantMapper extends BaseMapper<Merchant> {
    default int updatePendingAudit(String status, String reason, String id) {
        return update(null, Wrappers.<Merchant>lambdaUpdate()
                .set(Merchant::getAuditStatus, status).set(Merchant::getAuditReason, reason)
                .eq(Merchant::getId, id).eq(Merchant::getAuditStatus, "待审核"));
    }

    @Select("SELECT id FROM merchant WHERE id=#{id} FOR UPDATE")
    String lockStore(@Param("id") String id);

    @Update("""
        UPDATE merchant_profile_revision SET status=#{status},reason=#{reason}
        WHERE merchant_id=#{id} AND status='待审核'
        """)
    int reviewRevision(@Param("id") String id, @Param("status") String status, @Param("reason") String reason);

    @Update("""
        UPDATE merchant m JOIN merchant_profile_revision r ON r.merchant_id=m.id
        LEFT JOIN finance_account a ON a.id=m.id AND a.type='商户'
        SET m.name=r.name,m.contact=r.contact,m.phone=r.phone,m.address=r.address,a.name=r.name
        WHERE m.id=#{id}
        """)
    void applyRevision(@Param("id") String id);

    @Update("""
        INSERT INTO merchant_store_photo(merchant_id,image_data,media_type)
        SELECT src.merchant_id,src.image_data,src.media_type
        FROM (SELECT merchant_id,image_data,media_type FROM merchant_profile_revision
              WHERE merchant_id=#{id} AND image_data IS NOT NULL) src
        ON DUPLICATE KEY UPDATE image_data=src.image_data,media_type=src.media_type
        """)
    void applyPhotoRevision(@Param("id") String id);

    @Select("SELECT image_data,media_type FROM merchant_store_photo WHERE merchant_id=#{id}")
    Map<String, Object> currentPhoto(@Param("id") String id);

    @Select("""
        SELECT COALESCE(r.image_data,p.image_data) AS image_data,COALESCE(r.media_type,p.media_type) AS media_type
        FROM merchant_profile_revision r LEFT JOIN merchant_store_photo p ON p.merchant_id=r.merchant_id
        WHERE r.merchant_id=#{id} AND r.status='待审核'
        """)
    Map<String, Object> revisionPhoto(@Param("id") String id);

    @Select("SELECT COUNT(*) FROM merchant_profile_revision WHERE status='待审核'")
    Integer countPendingRevisions();

    default Integer countPending() {
        return Math.toIntExact(selectCount(Wrappers.<Merchant>lambdaQuery().eq(Merchant::getAuditStatus, "待审核")))
                + countPendingRevisions();
    }
}

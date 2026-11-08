package com.campusfood.admin.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.campusfood.admin.entity.Merchant;
import org.apache.ibatis.annotations.Mapper;

/** 商户审核及待办统计。 */
@Mapper
public interface MerchantMapper extends BaseMapper<Merchant> {
    default int updatePendingAudit(String status, String reason, String id) {
        return update(null, Wrappers.<Merchant>lambdaUpdate()
                .set(Merchant::getAuditStatus, status).set(Merchant::getAuditReason, reason)
                .eq(Merchant::getId, id).eq(Merchant::getAuditStatus, "待审核"));
    }

    default Integer countPending() {
        return Math.toIntExact(selectCount(Wrappers.<Merchant>lambdaQuery().eq(Merchant::getAuditStatus, "待审核")));
    }
}

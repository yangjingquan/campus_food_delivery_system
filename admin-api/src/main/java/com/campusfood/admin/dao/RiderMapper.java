package com.campusfood.admin.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.campusfood.admin.entity.Rider;
import org.apache.ibatis.annotations.Mapper;

/** 骑手审核及待办统计。 */
@Mapper
public interface RiderMapper extends BaseMapper<Rider> {
    default int updatePendingAudit(String status, String reason, String id) {
        return update(null, Wrappers.<Rider>lambdaUpdate()
                .set(Rider::getAuditStatus, status).set(Rider::getAuditReason, reason)
                .eq(Rider::getId, id).eq(Rider::getAuditStatus, "待审核"));
    }

    default Integer countPending() {
        return Math.toIntExact(selectCount(Wrappers.<Rider>lambdaQuery().eq(Rider::getAuditStatus, "待审核")));
    }
}

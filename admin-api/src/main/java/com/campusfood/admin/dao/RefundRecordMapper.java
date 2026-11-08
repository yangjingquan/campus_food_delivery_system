package com.campusfood.admin.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.campusfood.admin.entity.RefundRecord;
import org.apache.ibatis.annotations.Mapper;

/** 退款记录的单表条件查询。 */
@Mapper
public interface RefundRecordMapper extends BaseMapper<RefundRecord> {
    default Integer countFailed() {
        return Math.toIntExact(selectCount(Wrappers.<RefundRecord>lambdaQuery().eq(RefundRecord::getStatus, "退款失败")));
    }
}

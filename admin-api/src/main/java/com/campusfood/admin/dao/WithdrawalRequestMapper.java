package com.campusfood.admin.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.campusfood.admin.entity.WithdrawalRequest;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;
import java.util.Map;

/** 提现申请状态查询与更新。 */
@Mapper
public interface WithdrawalRequestMapper extends BaseMapper<WithdrawalRequest> {
    default Integer countPending() {
        return Math.toIntExact(selectCount(Wrappers.<WithdrawalRequest>lambdaQuery()
                .eq(WithdrawalRequest::getStatus, "待审核")));
    }

    default List<Map<String, Object>> lockWithdrawal(String id) {
        return selectMaps(Wrappers.<WithdrawalRequest>lambdaQuery()
                .select(WithdrawalRequest::getId, WithdrawalRequest::getAccountId,
                        WithdrawalRequest::getAmountCents, WithdrawalRequest::getStatus)
                .eq(WithdrawalRequest::getId, id).last("FOR UPDATE"));
    }

    default List<Map<String, Object>> lockStatus(String id) {
        return selectMaps(Wrappers.<WithdrawalRequest>lambdaQuery()
                .select(WithdrawalRequest::getId, WithdrawalRequest::getStatus)
                .eq(WithdrawalRequest::getId, id).last("FOR UPDATE"));
    }

    default int updateStatus(String status, String reason, String id) {
        return update(null, Wrappers.<WithdrawalRequest>lambdaUpdate()
                .set(WithdrawalRequest::getStatus, status).set(WithdrawalRequest::getReason, reason)
                .eq(WithdrawalRequest::getId, id));
    }

    default int markTransferring(String id) {
        return updateStatus("打款中", "等待渠道结果", id);
    }

    default int markUnknown(String id) {
        return update(null, Wrappers.<WithdrawalRequest>lambdaUpdate()
                .set(WithdrawalRequest::getReason, "渠道结果未知，需查询原转账单")
                .eq(WithdrawalRequest::getId, id));
    }
}

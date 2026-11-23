package com.campusfood.admin.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.campusfood.admin.entity.WithdrawalRequest;
import com.campusfood.admin.entity.WithdrawalStates.Request;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;
import java.util.Map;

/** 提现申请状态查询与更新。 */
@Mapper
public interface WithdrawalRequestMapper extends BaseMapper<WithdrawalRequest> {
    default Integer countPending() {
        return Math.toIntExact(selectCount(Wrappers.<WithdrawalRequest>lambdaQuery()
                .eq(WithdrawalRequest::getStatus, Request.PENDING_REVIEW)));
    }

    default boolean hasInFlight(String accountId) {
        return exists(Wrappers.<WithdrawalRequest>lambdaQuery().eq(WithdrawalRequest::getAccountId, accountId)
                .in(WithdrawalRequest::getStatus, Request.PENDING_REVIEW, Request.PENDING_PAYOUT, Request.TRANSFERRING, Request.AWAITING_CONFIRMATION));
    }

    default List<Map<String, Object>> lockWithdrawal(String id) {
        return selectMaps(Wrappers.<WithdrawalRequest>lambdaQuery()
                .select(WithdrawalRequest::getId, WithdrawalRequest::getAccountId,
                        WithdrawalRequest::getAmountCents, WithdrawalRequest::getStatus,
                        WithdrawalRequest::getReason, WithdrawalRequest::getCreatedAt)
                .eq(WithdrawalRequest::getId, id).last("FOR UPDATE"));
    }

    default boolean hasPayoutRetry(String id) {
        return exists(Wrappers.<WithdrawalRequest>lambdaQuery()
                .eq(WithdrawalRequest::getRequestKey, "payout-retry-" + id));
    }

    default List<Map<String, Object>> lockStatus(String id) {
        return selectMaps(Wrappers.<WithdrawalRequest>lambdaQuery()
                .select(WithdrawalRequest::getId, WithdrawalRequest::getStatus)
                .eq(WithdrawalRequest::getId, id).last("FOR UPDATE"));
    }

    default int updateStatus(Request status, String reason, String id) {
        return update(null, Wrappers.<WithdrawalRequest>lambdaUpdate()
                .set(WithdrawalRequest::getStatus, status).set(WithdrawalRequest::getReason, reason)
                .eq(WithdrawalRequest::getId, id));
    }

    default int markTransferring(String id) {
        return updateStatus(Request.TRANSFERRING, "等待渠道结果", id);
    }

    default int markUnknown(String id) {
        return update(null, Wrappers.<WithdrawalRequest>lambdaUpdate()
                .set(WithdrawalRequest::getReason, "渠道结果未知，需查询原转账单")
                .eq(WithdrawalRequest::getId, id));
    }
}

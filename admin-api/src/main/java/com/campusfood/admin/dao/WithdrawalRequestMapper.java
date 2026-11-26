package com.campusfood.admin.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.campusfood.admin.entity.WithdrawalRequest;
import com.campusfood.domain.BusinessStates.Withdrawal;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;
import java.util.Map;

/** 提现申请状态查询与更新。 */
@Mapper
public interface WithdrawalRequestMapper extends BaseMapper<WithdrawalRequest> {
    default Integer countPending() {
        return Math.toIntExact(selectCount(Wrappers.<WithdrawalRequest>lambdaQuery()
                .eq(WithdrawalRequest::getStatus, Withdrawal.PENDING_REVIEW)));
    }

    default boolean hasInFlight(String accountId) {
        return !selectObjs(Wrappers.<WithdrawalRequest>lambdaQuery().select(WithdrawalRequest::getId)
                .eq(WithdrawalRequest::getAccountId, accountId)
                .in(WithdrawalRequest::getStatus, Withdrawal.PENDING_REVIEW, Withdrawal.PENDING_PAYOUT,
                        Withdrawal.TRANSFERRING, Withdrawal.AWAITING_CONFIRMATION)
                .last("LIMIT 1 FOR SHARE")).isEmpty();
    }

    default List<Map<String, Object>> lockWithdrawal(String id) {
        return selectMaps(Wrappers.<WithdrawalRequest>lambdaQuery()
                .select(WithdrawalRequest::getId, WithdrawalRequest::getAccountId,
                        WithdrawalRequest::getApplicantType, WithdrawalRequest::getAmountCents, WithdrawalRequest::getStatus,
                        WithdrawalRequest::getReason, WithdrawalRequest::getReasonCode, WithdrawalRequest::getCreatedAt,
                        WithdrawalRequest::getRecipientOpenId)
                .eq(WithdrawalRequest::getId, id).last("FOR UPDATE"));
    }

    default boolean hasPayoutRetry(String id) {
        return exists(Wrappers.<WithdrawalRequest>lambdaQuery()
                .eq(WithdrawalRequest::getRequestKey, "payout-retry-" + id));
    }

    default int updateStatus(Withdrawal status, String reason, String id) {
        return update(null, Wrappers.<WithdrawalRequest>lambdaUpdate()
                .set(WithdrawalRequest::getStatus, status).set(WithdrawalRequest::getReason, reason).set(WithdrawalRequest::getReasonCode, null)
                .eq(WithdrawalRequest::getId, id));
    }

    default int markTransferring(String id) {
        return updateStatus(Withdrawal.TRANSFERRING, "等待渠道结果", id);
    }

    default int markUnknown(String id) {
        return update(null, Wrappers.<WithdrawalRequest>lambdaUpdate()
                .set(WithdrawalRequest::getReason, "渠道结果未知，需查询原转账单")
                .set(WithdrawalRequest::getReasonCode, null)
                .eq(WithdrawalRequest::getId, id));
    }
}

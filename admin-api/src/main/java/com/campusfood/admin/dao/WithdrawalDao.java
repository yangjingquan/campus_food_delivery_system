package com.campusfood.admin.dao;

import com.campusfood.domain.BusinessStates.WithdrawalIntent;
import com.campusfood.domain.BusinessStates.Withdrawal;
import com.campusfood.domain.BusinessStates.WithdrawalTransfer;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 提现、资金账本及渠道转账记录的数据访问接口。 */
@Mapper
public interface WithdrawalDao {
    int insertIntentIfAbsent(@Param("requestKey") String requestKey, @Param("accountId") String accountId,
                             @Param("requestedCents") long requestedCents, @Param("status") WithdrawalIntent status);

    int insertWithdrawal(@Param("id") String id, @Param("applicant") Object applicant,
                         @Param("applicantType") Object applicantType, @Param("amount") long amount,
                         @Param("status") Withdrawal status, @Param("createdAt") String createdAt,
                         @Param("accountId") String accountId, @Param("requestKey") String requestKey);

    int insertReviewAudit(@Param("actor") String actor, @Param("action") String action,
                          @Param("targetId") Object targetId, @Param("beforeState") String beforeState,
                          @Param("afterState") String afterState, @Param("reason") String reason);

    boolean hasReviewHistory(@Param("id") String id);

    boolean hasRetryRecipient(@Param("id") String id, @Param("accountId") String accountId, @Param("type") String type);

    boolean hasRiderChannel(@Param("id") String id);

    boolean hasRejectedRiderChannel(@Param("id") String id, @Param("appId") String appId,
                                    @Param("merchantNo") String merchantNo);

    long outstandingFrozen(@Param("accountId") String accountId, @Param("id") String id);

    int insertTransfer(@Param("transferId") String transferId, @Param("withdrawalId") String withdrawalId,
                       @Param("status") WithdrawalTransfer status);

    int insertChannelAudit(@Param("withdrawalId") String withdrawalId, @Param("afterState") String afterState,
                           @Param("reason") String reason);

    int insertJournal(@Param("accountId") String accountId, @Param("eventType") String eventType,
                      @Param("referenceId") String referenceId, @Param("availableDelta") long availableDelta,
                      @Param("frozenDelta") long frozenDelta, @Param("requestKey") String requestKey,
                      @Param("debtId") String debtId);
}

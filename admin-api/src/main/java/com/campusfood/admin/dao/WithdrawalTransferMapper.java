package com.campusfood.admin.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.campusfood.admin.entity.WithdrawalTransfer;
import com.campusfood.admin.entity.WithdrawalStates.Transfer;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;
import java.util.Map;

/** 渠道转账记录的查询与终态更新。 */
@Mapper
public interface WithdrawalTransferMapper extends BaseMapper<WithdrawalTransfer> {
    default List<Map<String, Object>> findTransfer(String id) {
        return selectMaps(Wrappers.<WithdrawalTransfer>lambdaQuery()
                .select(WithdrawalTransfer::getId, WithdrawalTransfer::getStatus)
                .eq(WithdrawalTransfer::getWithdrawalId, id));
    }

    default List<Map<String, Object>> lockTransfer(String id) {
        return selectMaps(Wrappers.<WithdrawalTransfer>lambdaQuery()
                .select(WithdrawalTransfer::getId, WithdrawalTransfer::getStatus, WithdrawalTransfer::getChannelReference)
                .eq(WithdrawalTransfer::getWithdrawalId, id).last("FOR UPDATE"));
    }

    default int markUnknown(String reason, String id) {
        return update(null, Wrappers.<WithdrawalTransfer>lambdaUpdate()
                .set(WithdrawalTransfer::getStatus, Transfer.UNKNOWN).set(WithdrawalTransfer::getReason, reason)
                .eq(WithdrawalTransfer::getWithdrawalId, id));
    }

    default int queueRetry(String id) {
        return update(null, Wrappers.<WithdrawalTransfer>lambdaUpdate()
                .set(WithdrawalTransfer::getRetryRequested, true)
                .eq(WithdrawalTransfer::getWithdrawalId, id)
                .eq(WithdrawalTransfer::getRetryRequested, false));
    }

    default int finalizeTransfer(Transfer status, String channelReference, String reason, String id) {
        return update(null, Wrappers.<WithdrawalTransfer>lambdaUpdate()
                .set(WithdrawalTransfer::getStatus, status).set(WithdrawalTransfer::getChannelReference, channelReference)
                .set(WithdrawalTransfer::getRetryRequested, false)
                .set(WithdrawalTransfer::getReason, reason).eq(WithdrawalTransfer::getWithdrawalId, id));
    }
}

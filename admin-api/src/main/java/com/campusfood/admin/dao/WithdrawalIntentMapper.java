package com.campusfood.admin.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.campusfood.admin.entity.WithdrawalIntent;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;
import java.util.Map;

/** 提现申请意图的幂等查询和状态更新。 */
@Mapper
public interface WithdrawalIntentMapper extends BaseMapper<WithdrawalIntent> {
    default Map<String, Object> lockIntent(String requestKey) {
        // 重复插入已持有共享锁；继续共享读取可避免并发重放时升级成排他锁而死锁。
        List<Map<String, Object>> rows = selectMaps(Wrappers.<WithdrawalIntent>lambdaQuery()
                .select(WithdrawalIntent::getAccountId, WithdrawalIntent::getRequestedCents,
                        WithdrawalIntent::getRecoveredCents, WithdrawalIntent::getAvailableAfter,
                        WithdrawalIntent::getStatus, WithdrawalIntent::getWithdrawalId)
                .eq(WithdrawalIntent::getRequestKey, requestKey).last("FOR SHARE"));
        return rows.isEmpty() ? null : rows.get(0);
    }

    default int markInsufficient(long recovered, long available, String requestKey) {
        return update(null, Wrappers.<WithdrawalIntent>lambdaUpdate()
                .set(WithdrawalIntent::getRecoveredCents, recovered).set(WithdrawalIntent::getAvailableAfter, available)
                .set(WithdrawalIntent::getStatus, "余额不足").eq(WithdrawalIntent::getRequestKey, requestKey));
    }

    default int markAccepted(long recovered, long available, String withdrawalId, String requestKey) {
        return update(null, Wrappers.<WithdrawalIntent>lambdaUpdate()
                .set(WithdrawalIntent::getRecoveredCents, recovered).set(WithdrawalIntent::getAvailableAfter, available)
                .set(WithdrawalIntent::getStatus, "已受理").set(WithdrawalIntent::getWithdrawalId, withdrawalId)
                .eq(WithdrawalIntent::getRequestKey, requestKey));
    }
}

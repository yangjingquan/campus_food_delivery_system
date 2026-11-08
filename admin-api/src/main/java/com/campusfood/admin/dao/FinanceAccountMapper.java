package com.campusfood.admin.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.campusfood.admin.entity.FinanceAccount;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;
import java.util.Map;

/** 账户余额与冻结金额的原子更新。 */
@Mapper
public interface FinanceAccountMapper extends BaseMapper<FinanceAccount> {
    default List<Map<String, Object>> lockForWithdrawal(String accountId) {
        return selectMaps(Wrappers.<FinanceAccount>lambdaQuery()
                .select(FinanceAccount::getId, FinanceAccount::getName, FinanceAccount::getType,
                        FinanceAccount::getBalanceCents, FinanceAccount::getStatus)
                .eq(FinanceAccount::getId, accountId).last("FOR UPDATE"));
    }

    default String lockAccount(String accountId) {
        List<Object> ids = selectObjs(Wrappers.<FinanceAccount>lambdaQuery()
                .select(FinanceAccount::getId).eq(FinanceAccount::getId, accountId).last("FOR UPDATE"));
        return ids.isEmpty() ? null : (String) ids.get(0);
    }

    default int debitAvailable(long amount, String accountId) {
        return update(null, Wrappers.<FinanceAccount>lambdaUpdate()
                .setDecrBy(FinanceAccount::getBalanceCents, amount).eq(FinanceAccount::getId, accountId));
    }

    default int creditPlatform(long amount) {
        return update(null, Wrappers.<FinanceAccount>lambdaUpdate()
                .setIncrBy(FinanceAccount::getBalanceCents, amount).eq(FinanceAccount::getId, "SYSTEM_PLATFORM"));
    }

    default int freezeAvailable(long amount, long frozenAmount, String accountId) {
        return update(null, Wrappers.<FinanceAccount>lambdaUpdate()
                .setDecrBy(FinanceAccount::getBalanceCents, amount)
                .setIncrBy(FinanceAccount::getFrozenCents, frozenAmount)
                .eq(FinanceAccount::getId, accountId));
    }

    default int unfreezeFunds(long availableAmount, long frozenAmount, String accountId, long minimumFrozen) {
        return update(null, Wrappers.<FinanceAccount>lambdaUpdate()
                .setIncrBy(FinanceAccount::getBalanceCents, availableAmount)
                .setDecrBy(FinanceAccount::getFrozenCents, frozenAmount)
                .eq(FinanceAccount::getId, accountId).ge(FinanceAccount::getFrozenCents, minimumFrozen));
    }

    default int consumeFrozen(long amount, String accountId, long minimumFrozen) {
        return update(null, Wrappers.<FinanceAccount>lambdaUpdate()
                .setDecrBy(FinanceAccount::getFrozenCents, amount)
                .eq(FinanceAccount::getId, accountId).ge(FinanceAccount::getFrozenCents, minimumFrozen));
    }
}

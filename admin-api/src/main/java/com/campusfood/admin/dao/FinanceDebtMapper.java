package com.campusfood.admin.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.campusfood.admin.entity.FinanceDebt;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;
import java.util.Map;

/** 商户欠款的锁定查询与扣回记录。 */
@Mapper
public interface FinanceDebtMapper extends BaseMapper<FinanceDebt> {
    default List<Map<String, Object>> lockOutstandingDebts(String accountId) {
        // 列与列比较使用固定 SQL 片段，片段不接受外部输入。
        return selectMaps(Wrappers.<FinanceDebt>lambdaQuery()
                .select(FinanceDebt::getId, FinanceDebt::getPlatformAdvanceCents, FinanceDebt::getRecoveredCents)
                .eq(FinanceDebt::getMerchantAccountId, accountId)
                .ltSql(FinanceDebt::getRecoveredCents, "platform_advance_cents")
                .orderByAsc(FinanceDebt::getId).last("FOR UPDATE"));
    }

    default int increaseDebtRecovered(long amount, String debtId) {
        return update(null, Wrappers.<FinanceDebt>lambdaUpdate()
                .setIncrBy(FinanceDebt::getRecoveredCents, amount).eq(FinanceDebt::getId, debtId));
    }
}

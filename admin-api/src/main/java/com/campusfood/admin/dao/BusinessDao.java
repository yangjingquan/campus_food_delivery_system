package com.campusfood.admin.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.campusfood.admin.entity.BusinessOrder;

import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 运营概览、订单、财务查询及运营配置的数据访问接口。 */
@Mapper
public interface BusinessDao extends BaseMapper<BusinessOrder> {
    List<Map<String, Object>> listOrders();

    List<String> listOrderNotes(@Param("orderId") Object orderId);

    Long sumPaid();

    default Integer countOrdersInPeriod(long start, long end) {
        return Math.toIntExact(selectCount(Wrappers.<BusinessOrder>lambdaQuery()
                .ge(BusinessOrder::getPaidAt, start).lt(BusinessOrder::getPaidAt, end)));
    }

    List<Map<String, Object>> listRefunds();

    List<Map<String, Object>> listWithdrawals();

    List<Map<String, Object>> listAccounts();

    List<Map<String, Object>> listLedger();

    List<Map<String, Object>> listJournal();

    List<Map<String, Object>> listDebts();

    List<Map<String, Object>> listOperationsConfigs();

    int insertOrderNote(@Param("orderId") Object orderId, @Param("actor") String actor, @Param("note") String note);

    int insertOperationsConfig(@Param("fee") int fee, @Param("foodRate") java.math.BigDecimal foodRate,
                               @Param("deliveryRate") java.math.BigDecimal deliveryRate,
                               @Param("regularBatch") int regularBatch, @Param("peakBatch") int peakBatch,
                               @Param("serviceStart") String serviceStart, @Param("serviceEnd") String serviceEnd,
                               @Param("peakStart") String peakStart, @Param("peakEnd") String peakEnd,
                               @Param("acceptTimeout") int acceptTimeout,
                               @Param("reminderInterval") int reminderInterval, @Param("actor") String actor);

    default Integer countRecordById(String table, String id) {
        if (!"business_order".equals(table)) throw new IllegalArgumentException("Invalid order table");
        return countOrderById(id);
    }

    default Integer countOrderById(String id) {
        return Math.toIntExact(selectCount(Wrappers.<BusinessOrder>lambdaQuery().eq(BusinessOrder::getId, id)));
    }

    int insertAudit(@Param("actor") String actor, @Param("action") String action, @Param("targetId") Object targetId,
                    @Param("beforeState") String beforeState, @Param("afterState") String afterState,
                    @Param("reason") String reason);

    default Integer countOrders() {
        return Math.toIntExact(selectCount(Wrappers.<BusinessOrder>lambdaQuery()));
    }

    default Integer countDeliveringOrders() {
        return Math.toIntExact(selectCount(Wrappers.<BusinessOrder>lambdaQuery()
                .in(BusinessOrder::getFulfillStatus, "配送中", "已到暂存点 / 待领取")));
    }

    default Integer countInterventionOrders() {
        return Math.toIntExact(selectCount(Wrappers.<BusinessOrder>lambdaQuery().eq(BusinessOrder::getIntervene, true)));
    }

}

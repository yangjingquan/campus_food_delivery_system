package com.campusfood.admin.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 运营配置表中用于版本锁定和保留原有结算时长的字段。 */
@TableName("operations_config")
public class OperationsConfig {
    @TableId
    private Long id;
    private int merchantSettlementWaitMinutes;
    private int riderSettlementWaitMinutes;

    public Long getId() {
        return id;
    }

    public int getMerchantSettlementWaitMinutes() {
        return merchantSettlementWaitMinutes;
    }

    public int getRiderSettlementWaitMinutes() {
        return riderSettlementWaitMinutes;
    }
}

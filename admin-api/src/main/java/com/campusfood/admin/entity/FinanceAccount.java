package com.campusfood.admin.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** finance_account 表的条件构造器字段映射。 */
@TableName("finance_account")
public class FinanceAccount {
    @TableId
    private String id;
    private String name;
    private String type;
    private Long balanceCents;
    private Long frozenCents;
    private String status;

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getType() {
        return type;
    }

    public Long getBalanceCents() {
        return balanceCents;
    }

    public Long getFrozenCents() {
        return frozenCents;
    }

    public String getStatus() {
        return status;
    }
}

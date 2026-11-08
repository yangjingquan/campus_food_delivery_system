package com.campusfood.admin.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** withdrawal_request 表的条件构造器字段映射。 */
@TableName("withdrawal_request")
public class WithdrawalRequest {
    @TableId
    private String id;
    private String accountId;
    private Long amountCents;
    private String status;
    private String reason;

    public String getId() {
        return id;
    }

    public String getAccountId() {
        return accountId;
    }

    public Long getAmountCents() {
        return amountCents;
    }

    public String getStatus() {
        return status;
    }

    public String getReason() {
        return reason;
    }
}

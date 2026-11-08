package com.campusfood.admin.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** withdrawal_intent 表的条件构造器字段映射。 */
@TableName("withdrawal_intent")
public class WithdrawalIntent {
    @TableId
    private String requestKey;
    private String accountId;
    private Long requestedCents;
    private Long recoveredCents;
    private Long availableAfter;
    private String status;
    private String withdrawalId;

    public String getRequestKey() {
        return requestKey;
    }

    public String getAccountId() {
        return accountId;
    }

    public Long getRequestedCents() {
        return requestedCents;
    }

    public Long getRecoveredCents() {
        return recoveredCents;
    }

    public Long getAvailableAfter() {
        return availableAfter;
    }

    public String getStatus() {
        return status;
    }

    public String getWithdrawalId() {
        return withdrawalId;
    }
}

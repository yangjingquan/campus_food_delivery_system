package com.campusfood.admin.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** withdrawal_transfer 表的条件构造器字段映射。 */
@TableName("withdrawal_transfer")
public class WithdrawalTransfer {
    @TableId
    private String id;
    private String withdrawalId;
    private String status;
    private String channelReference;
    private String reason;

    public String getId() {
        return id;
    }

    public String getWithdrawalId() {
        return withdrawalId;
    }

    public String getStatus() {
        return status;
    }

    public String getChannelReference() {
        return channelReference;
    }

    public String getReason() {
        return reason;
    }
}

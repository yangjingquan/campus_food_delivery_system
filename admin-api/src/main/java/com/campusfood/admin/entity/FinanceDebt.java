package com.campusfood.admin.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** finance_debt 表的条件构造器字段映射。 */
@TableName("finance_debt")
public class FinanceDebt {
    @TableId
    private String id;
    private String merchantAccountId;
    private Long platformAdvanceCents;
    private Long recoveredCents;

    public String getId() {
        return id;
    }

    public String getMerchantAccountId() {
        return merchantAccountId;
    }

    public Long getPlatformAdvanceCents() {
        return platformAdvanceCents;
    }

    public Long getRecoveredCents() {
        return recoveredCents;
    }
}

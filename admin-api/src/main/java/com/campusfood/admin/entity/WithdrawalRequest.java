package com.campusfood.admin.entity;

import com.campusfood.domain.BusinessStates.AccountType;
import com.campusfood.domain.BusinessStates.Withdrawal;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** withdrawal_request 表的条件构造器字段映射。 */
@TableName("withdrawal_request")
public class WithdrawalRequest {
    @TableId
    private String id;
    private String accountId;
    private String recipientOpenId;
    private AccountType applicantType;
    private Long amountCents;
    private Withdrawal status;
    private String reason;
    private String reasonCode;
    private String createdAt;
    private String requestKey;

    public String getId() {
        return id;
    }

    public String getAccountId() {
        return accountId;
    }

    public String getRecipientOpenId() {
        return recipientOpenId;
    }

    public AccountType getApplicantType() {
        return applicantType;
    }

    public Long getAmountCents() {
        return amountCents;
    }

    public Withdrawal getStatus() {
        return status;
    }

    public String getReason() {
        return reason;
    }

    public String getReasonCode() { return reasonCode; }

    public String getCreatedAt() {
        return createdAt;
    }

    public String getRequestKey() {
        return requestKey;
    }
}

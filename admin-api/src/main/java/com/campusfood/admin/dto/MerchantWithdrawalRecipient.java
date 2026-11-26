package com.campusfood.admin.dto;

/** 提现申请提交时确认的微信收款人。 */
public record MerchantWithdrawalRecipient(long identityId, String appId, String openId) {}

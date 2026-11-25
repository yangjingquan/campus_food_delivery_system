package com.campusfood.domain;

/** 通过微信商家转账提现的最低限额，金额单位为分。 */
public final class WithdrawalLimits {
    public static final long MINIMUM_CENTS = 10;
    public static final String RIDER_MINIMUM_MESSAGE = "骑手单笔提现最低 ¥0.10";
    public static final String MERCHANT_MINIMUM_MESSAGE = "商户单笔提现最低 ¥0.10";
    public static final String REJECTED_SUBMISSION_REASON = "微信转账提交异常：HTTP 400 / INVALID_REQUEST";
    public static final String RETURNED_BELOW_MINIMUM = "低于微信单笔转账最低金额 ¥0.10，已退回可用余额";

    private WithdrawalLimits() {}
}

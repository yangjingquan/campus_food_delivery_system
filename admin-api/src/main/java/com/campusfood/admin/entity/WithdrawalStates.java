package com.campusfood.admin.entity;

/** 提现各阶段使用独立的持久化枚举，中文标签用于接口兼容和审核说明。 */
public final class WithdrawalStates {
    private WithdrawalStates() {}

    public enum Request {
        PENDING_REVIEW("待审核"),
        REVIEW_REJECTED("审核拒绝"),
        PENDING_PAYOUT("待打款"),
        TRANSFERRING("打款中"),
        AWAITING_CONFIRMATION("待收款确认"),
        PAID("打款成功"),
        PAYOUT_FAILED("打款失败");

        private final String label;

        Request(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public enum Intent {
        PROCESSING("处理中"),
        ACCEPTED("已受理"),
        INSUFFICIENT_FUNDS("余额不足");

        private final String label;

        Intent(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public enum Transfer {
        PROCESSING("处理中"),
        UNKNOWN("未知"),
        SUCCEEDED("成功"),
        FAILED("失败");

        private final String label;

        Transfer(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        public boolean terminal() {
            return this == SUCCEEDED || this == FAILED;
        }
    }
}

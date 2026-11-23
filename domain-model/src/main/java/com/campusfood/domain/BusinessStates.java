package com.campusfood.domain;

/** Fixed codes shared by API consumers and the MySQL business schema. */
public final class BusinessStates {
    private BusinessStates() {}

    public interface Code {
        String name();

        default boolean is(Object value) {
            return name().equals(value);
        }
    }

    public enum Payment implements Code {
        UNPAID,
        PAYMENT_SUCCEEDED,
        MOCK_PAYMENT_SUCCEEDED,
        CLOSED
    }

    public enum Fulfillment implements Code {
        PENDING_PAYMENT,
        AWAITING_MERCHANT,
        PREPARING_FOOD,
        AWAITING_RIDER,
        AWAITING_PICKUP,
        DELIVERING,
        COMPLETED,
        CLOSED
    }

    public enum Refund implements Code {
        NO_AFTER_SALE,
        AFTER_SALE_ENDED,
        REFUND_SUCCEEDED,
        OFFLINE_RESOLVED,
        REFUND_FAILED,
        PLATFORM_REVIEW,
        AWAITING_MERCHANT_DECISION,
        REFUND_PROCESSING,
        REFUND_VERIFYING,
        REFUND_QUEUED,
        REFUND_REJECTED,
        NEGOTIATING
    }

    public enum Settlement implements Code {
        PENDING_PAYMENT,
        PENDING_SETTLEMENT,
        WITHDRAWABLE,
        SETTLED,
        WITHDRAWN,
        CLOSED,
        SETTLEMENT_HELD,
        PENDING_CREDIT
    }

    public enum Audit implements Code {
        PENDING_REVIEW,
        APPROVED,
        REJECTED,
        NOT_ENROLLED
    }

    public enum Business implements Code {
        STORE_CLOSED,
        STORE_OPEN,
        PROFILE_REVISION
    }

    public enum AccountType implements Code {
        RIDER,
        MERCHANT,
        PLATFORM
    }

    public enum AccountStatus implements Code {
        ACCOUNT_ACTIVE,
        ACCOUNT_DISABLED,
        ACCOUNT_FROZEN
    }

    public enum Gender implements Code {
        MALE,
        FEMALE,
        UNDISCLOSED
    }

    public enum Availability implements Code {
        MIDDAY,
        EVENING,
        MIDDAY_AND_EVENING,
        CUSTOM
    }

    public enum RefundChannel implements Code {
        REFUND_SUBMISSION_PENDING,
        REFUND_NOT_STARTED,
        MERCHANT_DECLINED,
        REFUND_FAILED,
        REFUND_RESULT_UNKNOWN,
        OFFLINE_VERIFIED,
        REFUND_CHANNEL_UNCONFIGURED,
        REFUND_LIMIT_REACHED,
        REFUND_INTERVAL_LIMIT,
        WECHAT_REFUND_SUBMISSION_PENDING,
        MOCK_REFUND_PROCESSING,
        WECHAT_REFUND_CONFIRMATION_PENDING,
        WECHAT_REFUND_PROCESSING,
        WECHAT_REFUND_SUCCEEDED,
        MOCK_REFUND_SUCCEEDED
    }

    public enum RefundEvent implements Code {
        AFTER_SALE_REQUESTED,
        MERCHANT_REFUND_DECLINED,
        MERCHANT_REFUND_APPROVED,
        USER_ESCALATED,
        AFTER_SALE_ENDED,
        ONLINE_REFUND_FAILED,
        REFUND_VERIFICATION_REQUIRED,
        OFFLINE_RESOLVED,
        MERCHANT_TIMEOUT_ESCALATED,
        MERCHANT_REFUND_CONFIRMED,
        ORDER_TIMEOUT_REFUND_REQUESTED,
        REFUND_CHANNEL_UNCONFIGURED,
        PLATFORM_REVIEW,
        REFUND_QUEUED,
        REFUND_SUCCEEDED_ORDER_CLOSED
    }

    public enum RefundOperator implements Code {
        USER_REQUEST,
        MERCHANT_ACTION,
        USER_ESCALATED,
        USER_ENDED,
        REFUND_CHANNEL,
        SYSTEM_ESCALATED,
        MERCHANT_ENDED,
        MERCHANT_NEGOTIATED,
        SYSTEM_TIMEOUT,
        OFFLINE_ADMIN
    }

    public enum Intervention implements Code {
        PICKUP_OVERDUE,
        USER_ESCALATED,
        REFUND_FAILED_OFFLINE_REQUIRED,
        MERCHANT_REFUND_TIMEOUT,
        REFUND_CHANNEL_UNCONFIGURED,
        REFUND_LIMIT_REACHED
    }

    public enum WithdrawalReason implements Code {
        WECHAT_FUNDS_INSUFFICIENT,
        SUBMISSION_BLOCKED,
        INVALID_APPLICATION_TIME,
        WECHAT_SUBMISSION_ERROR
    }

    public enum Withdrawal implements Code {
        PENDING_REVIEW,
        REVIEW_REJECTED,
        PENDING_PAYOUT,
        TRANSFERRING,
        AWAITING_CONFIRMATION,
        PAID,
        PAYOUT_FAILED,
        CONFIRMATION_UNKNOWN
    }

    public enum WithdrawalIntent implements Code {
        PROCESSING,
        ACCEPTED,
        INSUFFICIENT_FUNDS
    }

    public enum WithdrawalTransfer implements Code {
        PROCESSING,
        UNKNOWN,
        SUCCEEDED,
        FAILED;

        public boolean terminal() {
            return this == SUCCEEDED || this == FAILED;
        }
    }

    public enum Reminder implements Code {
        SENDING,
        SENT,
        FAILED
    }

    public enum OrderActor implements Code {
        MERCHANT,
        RIDER_SCAN,
        RIDER_DELIVERY,
        FULFILLMENT_SERVICE
    }

    public enum OrderFilter implements Code {
        ALL,
        IN_PROGRESS,
        AFTER_SALE,
        PENDING_PAYMENT,
        UNPAID,
        COMPLETED,
        CLOSED,
        AWAITING_MERCHANT,
        PREPARING_FOOD,
        AWAITING_RIDER,
        AWAITING_PICKUP,
        DELIVERING,
        PAYMENT_SUCCEEDED
    }

    public enum AdminAudit implements Code {
        UPDATED,
        RESET,
        PREVIOUS_VERSION,
        NEW_VERSION,
        ENABLED,
        DISABLED
    }
}

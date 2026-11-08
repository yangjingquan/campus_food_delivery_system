package com.campusfood.admin.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 商户订单表的 MyBatis-Plus 映射，金额字段以分为单位。 */
@TableName("business_order")
public class BusinessOrder {
    @TableId
    private String id;
    private String paymentId;
    private String merchantId;
    private String merchant;
    private String rider;
    private String items;
    private Long foodCents;
    private Long deliveryCents;
    private Long paidCents;
    private Long refundedCents;
    private Long foodRefundedCents;
    private String paymentStatus;
    private String fulfillStatus;
    private String refundStatus;
    private String settlementStatus;
    private Boolean accepted;
    private Boolean pendingRefund;
    private Boolean locking;
    private Integer paymentCount;
    private Long paidAt;
    private Long completedAt;
    private Long closedAt;
    private Long lastRefundAt;
    private Long paymentLastRefundAt;
    private Boolean intervene;
    private String interveneReason;

    public String getId() { return id; }

    public Long getPaidAt() { return paidAt; }

    public String getFulfillStatus() { return fulfillStatus; }

    public Boolean getIntervene() { return intervene; }
}

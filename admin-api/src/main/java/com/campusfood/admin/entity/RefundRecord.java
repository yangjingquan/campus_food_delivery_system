package com.campusfood.admin.entity;

import com.campusfood.domain.BusinessStates.Refund;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** refund_record 表的条件构造器字段映射。 */
@TableName("refund_record")
public class RefundRecord {
    @TableId
    private String id;
    private Refund status;

    public String getId() {
        return id;
    }

    public Refund getStatus() {
        return status;
    }
}

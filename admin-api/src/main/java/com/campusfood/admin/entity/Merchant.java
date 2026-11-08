package com.campusfood.admin.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** merchant 表的条件构造器字段映射。 */
@TableName("merchant")
public class Merchant {
    @TableId
    private String id;
    private String auditStatus;
    private String auditReason;

    public String getId() {
        return id;
    }

    public String getAuditStatus() {
        return auditStatus;
    }

    public String getAuditReason() {
        return auditReason;
    }
}

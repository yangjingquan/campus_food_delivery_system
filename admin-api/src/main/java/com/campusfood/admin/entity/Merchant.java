package com.campusfood.admin.entity;

import com.campusfood.domain.BusinessStates.Audit;
import com.campusfood.domain.BusinessStates.Business;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** merchant 表的条件构造器字段映射。 */
@TableName("merchant")
public class Merchant {
    @TableId
    private String id;
    private Audit auditStatus;
    private String auditReason;
    private String name;
    private String contact;
    private String phone;
    private String address;
    private Business businessStatus;
    private String joinedAt;
    private Boolean listed;
    private Integer displayOrder;
    private LocalDateTime deletedAt;

    public static Merchant create(String id, String name, String contact, String phone, String address,
                                  Business businessStatus, String joinedAt) {
        Merchant merchant = new Merchant();
        merchant.id = id;
        merchant.name = name;
        merchant.contact = contact;
        merchant.phone = phone;
        merchant.address = address;
        merchant.joinedAt = joinedAt;
        merchant.auditStatus = Audit.APPROVED;
        merchant.businessStatus = businessStatus;
        merchant.listed = false;
        return merchant;
    }

    public String getId() {
        return id;
    }

    public Audit getAuditStatus() {
        return auditStatus;
    }

    public String getAuditReason() {
        return auditReason;
    }

    public String getName() { return name; }
    public String getContact() { return contact; }
    public String getPhone() { return phone; }
    public String getAddress() { return address; }
    public Business getBusinessStatus() { return businessStatus; }
    public Integer getDisplayOrder() { return displayOrder; }
    public Boolean getListed() { return listed; }
    public LocalDateTime getDeletedAt() { return deletedAt; }
}

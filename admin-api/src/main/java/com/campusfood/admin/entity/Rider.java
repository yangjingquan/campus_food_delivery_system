package com.campusfood.admin.entity;

import com.campusfood.domain.BusinessStates.Audit;
import com.campusfood.domain.BusinessStates.Availability;
import com.campusfood.domain.BusinessStates.Gender;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** rider 表的条件构造器字段映射。 */
@TableName("rider")
public class Rider {
    @TableId
    private String id;
    private String name;
    private String phone;
    private Gender gender;
    private Availability availability;
    private String availableTime;
    private Boolean enabled;
    private String joinedAt;
    private Audit auditStatus;
    private String auditReason;

    public static Rider create(String id, String name, String phone, Gender gender, Availability availability,
            String availableTime, String joinedAt) {
        Rider rider = new Rider();
        rider.id = id;
        rider.name = name;
        rider.phone = phone;
        rider.gender = gender;
        rider.availability = availability;
        rider.availableTime = availableTime;
        rider.joinedAt = joinedAt;
        rider.auditStatus = Audit.NOT_ENROLLED;
        return rider;
    }

    public String getName() { return name; }
    public String getPhone() { return phone; }
    public Gender getGender() { return gender; }
    public Availability getAvailability() { return availability; }
    public String getAvailableTime() { return availableTime; }
    public Boolean getEnabled() { return enabled; }
    public String getJoinedAt() { return joinedAt; }

    public String getId() {
        return id;
    }

    public Audit getAuditStatus() {
        return auditStatus;
    }

    public String getAuditReason() {
        return auditReason;
    }
}

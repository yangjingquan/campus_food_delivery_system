package com.campusfood.rider.dao;

import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface RiderDao {
    @Select("""
        SELECT i.id, r.name, r.phone, r.gender, r.available_time AS availableTime,
               r.audit_status AS auditStatus, r.audit_reason AS auditReason, r.joined_at AS joinedAt
        FROM rider_identity i LEFT JOIN rider r ON r.id=i.id WHERE i.id=#{id}
        """)
    Map<String, Object> profile(@Param("id") String id);
}

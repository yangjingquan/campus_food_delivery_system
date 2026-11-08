package com.campusfood.merchant.dao;

import java.util.Map;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface MerchantSessionMapper {
    @Insert("""
        INSERT INTO merchant_identity(app_id,open_id) VALUES(#{appId},#{openId})
        ON DUPLICATE KEY UPDATE id=id
        """)
    void ensureIdentity(@Param("appId") String appId, @Param("openId") String openId);

    @Select("SELECT id FROM merchant_identity WHERE app_id=#{appId} AND open_id=#{openId}")
    long identityId(@Param("appId") String appId, @Param("openId") String openId);

    @Insert("""
        INSERT INTO merchant_session(token_hash,identity_id,expires_at)
        VALUES(#{hash},#{identityId},#{expiresAt})
        """)
    void save(@Param("hash") String hash, @Param("identityId") long identityId, @Param("expiresAt") long expiresAt);

    @Select("""
        SELECT i.id,i.merchant_id,m.audit_status
        FROM merchant_session s JOIN merchant_identity i ON i.id=s.identity_id
        LEFT JOIN merchant m ON m.id=i.merchant_id
        WHERE s.token_hash=#{hash} AND s.expires_at>#{now}
        """)
    Map<String, Object> findActive(@Param("hash") String hash, @Param("now") long now);

    @Delete("DELETE FROM merchant_session WHERE token_hash=#{hash}")
    void revoke(@Param("hash") String hash);

    @Delete("DELETE FROM merchant_session WHERE expires_at<=#{now}")
    void expire(@Param("now") long now);
}

package com.campusfood.admin.dao;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface PrintingSettingsDao {
    record Credentials(String appId, Long secretId) {}

    @Select("SELECT app_id AS appId, secret_id AS secretId FROM printing_settings WHERE id = 1")
    Credentials current();

    @Select("SELECT app_id AS appId, secret_id AS secretId FROM printing_settings WHERE id = 1 FOR UPDATE")
    Credentials lockCurrent();

    @Update("UPDATE printing_settings SET app_id = #{appId}, secret_id = #{secretId} WHERE id = 1")
    void save(@Param("appId") String appId, @Param("secretId") Long secretId);
}

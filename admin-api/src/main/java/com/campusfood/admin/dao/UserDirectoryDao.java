package com.campusfood.admin.dao;

import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface UserDirectoryDao {
    long count(@Param("role") String role, @Param("keyword") String keyword);

    List<Map<String, Object>> list(@Param("role") String role, @Param("keyword") String keyword,
                                   @Param("limit") int limit, @Param("offset") long offset);
}

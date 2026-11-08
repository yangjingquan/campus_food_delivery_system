package com.campusfood.admin.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.campusfood.admin.entity.AdminUser;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 管理员角色和权限的数据查询接口。 */
@Mapper
public interface AuthorizationDao extends BaseMapper<AdminUser> {
    List<String> findPermissions(@Param("username") String username);

    List<String> findRoles(@Param("username") String username);

    default Integer countActiveUser(String username) {
        return Math.toIntExact(selectCount(Wrappers.<AdminUser>lambdaQuery()
                .eq(AdminUser::getUsername, username).eq(AdminUser::getEnabled, true)));
    }
}

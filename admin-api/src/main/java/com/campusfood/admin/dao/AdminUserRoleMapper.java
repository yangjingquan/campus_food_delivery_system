package com.campusfood.admin.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.campusfood.admin.entity.AdminUserRole;
import org.apache.ibatis.annotations.Mapper;
import java.util.List;

/** 管理员与角色关联的条件操作。 */
@Mapper
public interface AdminUserRoleMapper extends BaseMapper<AdminUserRole> {
    default Integer countRoles() {
        return Math.toIntExact(selectCount(Wrappers.<AdminUserRole>lambdaQuery()));
    }

    default List<String> findRoleCodes(long userId) {
        return selectObjs(Wrappers.<AdminUserRole>lambdaQuery().select(AdminUserRole::getRoleCode)
                .eq(AdminUserRole::getUserId, userId).orderByAsc(AdminUserRole::getRoleCode))
                .stream().map(String.class::cast).toList();
    }

    default int deleteRoles(long userId) {
        return delete(Wrappers.<AdminUserRole>lambdaQuery().eq(AdminUserRole::getUserId, userId));
    }
}

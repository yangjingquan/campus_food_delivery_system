package com.campusfood.admin.dao;

import com.campusfood.domain.BusinessStates.Audit;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.campusfood.admin.entity.AdminUser;

import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 管理员、角色分配及入驻审核的数据访问接口。 */
@Mapper
public interface AdminDao extends BaseMapper<AdminUser> {
    default Integer countUsersByUsername(String username) {
        return Math.toIntExact(selectCount(Wrappers.<AdminUser>lambdaQuery().eq(AdminUser::getUsername, username)));
    }

    int insertUser(@Param("username") String username, @Param("passwordHash") String passwordHash);

    default String findSessionUsername(String username, String sessionVersion) {
        var matches = selectObjs(Wrappers.<AdminUser>lambdaQuery().select(AdminUser::getUsername)
                .eq(AdminUser::getUsername, username).eq(AdminUser::getEnabled, true)
                .eq(AdminUser::getSessionVersion, sessionVersion));
        return matches.isEmpty() ? null : (String) matches.getFirst();
    }

    default List<Map<String, Object>> findEnabledCredentials(String username) {
        return selectMaps(Wrappers.<AdminUser>lambdaQuery()
                .select(AdminUser::getUsername, AdminUser::getPasswordHash, AdminUser::getSessionVersion)
                .eq(AdminUser::getUsername, username).eq(AdminUser::getEnabled, true));
    }

    default String lockPasswordHash(String username) {
        List<Object> rows = selectObjs(Wrappers.<AdminUser>lambdaQuery()
                .select(AdminUser::getPasswordHash).eq(AdminUser::getUsername, username).last("FOR UPDATE"));
        return rows.isEmpty() ? null : (String) rows.get(0);
    }

    default int changePasswordByUsername(String passwordHash, String username) {
        return update(null, Wrappers.<AdminUser>lambdaUpdate()
                .set(AdminUser::getPasswordHash, passwordHash).setIncrBy(AdminUser::getSessionVersion, 1)
                .eq(AdminUser::getUsername, username));
    }

    int insertSimpleAudit(@Param("actor") String actor, @Param("action") String action,
                          @Param("targetId") Object targetId, @Param("afterState") String afterState);

    @Select("""
        SELECT EXISTS(SELECT 1 FROM information_schema.columns
            WHERE table_schema=DATABASE() AND table_name=#{table} AND column_name=#{column})
        """)
    boolean hasBindingColumn(@Param("table") String table, @Param("column") String column);

    List<Map<String, Object>> listMerchants(@Param("bindings") boolean bindings);

    List<Map<String, Object>> listRiders(@Param("bindings") boolean bindings);

    List<Map<String, Object>> listAdministrators();

    List<Map<String, Object>> listRoleAssignments();

    List<Map<String, Object>> listAdministratorAudit();

    int assignRoleByUsername(@Param("role") String role, @Param("username") String username);

    default List<Map<String, Object>> lockAdministrator(long userId) {
        return selectMaps(Wrappers.<AdminUser>lambdaQuery()
                .select(AdminUser::getId, AdminUser::getUsername, AdminUser::getEnabled)
                .eq(AdminUser::getId, userId).last("FOR UPDATE"));
    }

    Integer countEnabledSupers();

    default int setAdministratorEnabled(boolean enabled, long userId) {
        return update(null, Wrappers.<AdminUser>lambdaUpdate()
                .set(AdminUser::getEnabled, enabled).setIncrBy(AdminUser::getSessionVersion, 1)
                .eq(AdminUser::getId, userId));
    }

    int assignRoleById(@Param("userId") long userId, @Param("role") String role);

    default int resetPasswordById(String passwordHash, long userId) {
        return update(null, Wrappers.<AdminUser>lambdaUpdate()
                .set(AdminUser::getPasswordHash, passwordHash).setIncrBy(AdminUser::getSessionVersion, 1)
                .eq(AdminUser::getId, userId));
    }

    int insertAudit(@Param("actor") String actor, @Param("action") String action, @Param("targetId") Object targetId,
                    @Param("beforeState") Audit beforeState, @Param("afterState") Audit afterState,
                    @Param("reason") String reason);
}

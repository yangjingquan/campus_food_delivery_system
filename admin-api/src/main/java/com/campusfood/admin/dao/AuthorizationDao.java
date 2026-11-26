package com.campusfood.admin.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.campusfood.admin.entity.AdminUser;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.*;

/** 管理员角色、权限及关联关系的数据访问接口。 */
@Mapper
public interface AuthorizationDao extends BaseMapper<AdminUser> {
    List<String> findPermissions(@Param("username") String username);

    List<String> findRoles(@Param("username") String username);

    default Integer countActiveUser(String username) {
        return Math.toIntExact(selectCount(Wrappers.<AdminUser>lambdaQuery()
                .eq(AdminUser::getUsername, username).eq(AdminUser::getEnabled, true)));
    }

    @Select("SELECT code,name,description FROM admin_role ORDER BY code <> 'super', name, code")
    List<Map<String, Object>> listRoles();

    @Select("SELECT code,description FROM admin_permission ORDER BY code")
    List<Map<String, Object>> listPermissions();

    @Select("SELECT role_code AS roleCode,permission_code AS permissionCode FROM admin_role_permission ORDER BY role_code,permission_code")
    List<Map<String, Object>> listRolePermissions();

    @Insert("INSERT INTO admin_role(code,name,description) VALUES (#{code},#{name},#{description})")
    void insertRole(@Param("code") String code, @Param("name") String name, @Param("description") String description);

    @Update("UPDATE admin_role SET name=#{name},description=#{description} WHERE code=#{code}")
    void updateRole(@Param("code") String code, @Param("name") String name, @Param("description") String description);

    @Delete("DELETE FROM admin_role WHERE code=#{code}")
    void deleteRole(@Param("code") String code);

    @Select("SELECT COUNT(*) FROM admin_user_role WHERE role_code=#{code}")
    int countAssignedUsers(@Param("code") String code);

    @Delete("DELETE FROM admin_role_permission WHERE role_code=#{code}")
    void clearPermissions(@Param("code") String code);

    @Insert("INSERT INTO admin_role_permission(role_code,permission_code) VALUES (#{code},#{permission})")
    void insertPermission(@Param("code") String code, @Param("permission") String permission);

    @Update("""
            UPDATE admin_user u JOIN admin_user_role ur ON ur.user_id=u.id
            SET u.session_version=u.session_version+1 WHERE ur.role_code=#{code}
            """)
    void revokeSessionsForRole(@Param("code") String code);
}

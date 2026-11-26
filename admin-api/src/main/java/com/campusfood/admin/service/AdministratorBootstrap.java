package com.campusfood.admin.service;

import com.campusfood.admin.dao.AdminDao;
import com.campusfood.admin.dao.AdminUserRoleMapper;
import com.campusfood.admin.dao.ConfigurationLockMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 在空数据库中创建首个超级管理员。 */
@Component
public class AdministratorBootstrap implements ApplicationRunner {
    private final AdminDao users;
    private final AdminUserRoleMapper assignments;
    private final ConfigurationLockMapper locks;
    private final String username;
    private final String password;

    public AdministratorBootstrap(AdminDao users, AdminUserRoleMapper assignments, ConfigurationLockMapper locks,
            @Value("${app.bootstrap-user}") String username, @Value("${app.bootstrap-password}") String password) {
        this.users = users;
        this.assignments = assignments;
        this.locks = locks;
        this.username = username;
        this.password = password;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        locks.lock("admin");
        if (users.selectCount(null) > 0) {
            // 兼容旧版本已经创建账号、尚未完成角色初始化的数据库。
            if (assignments.countRoles() == 0 && !users.findEnabledCredentials(username).isEmpty()) {
                users.assignRoleByUsername("super", username);
            }
            return;
        }

        if (!username.matches("[A-Za-z0-9_.-]{3,64}") || password.isBlank()) {
            throw new IllegalStateException("空管理员数据库需要配置有效的 ADMIN_USER 和 ADMIN_PASSWORD");
        }
        AdminService.validatePassword(password);
        users.insertUser(username, new BCryptPasswordEncoder().encode(password));
        users.assignRoleByUsername("super", username);
    }
}

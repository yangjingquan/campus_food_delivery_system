package com.campusfood.admin.service;

import java.util.List;

import org.springframework.http.HttpStatus;
import com.campusfood.admin.dao.AuthorizationDao;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** 根据当前启用的角色查询和校验管理员权限。 */
@Service
public class AuthorizationService {
    private final AuthorizationDao db;

    public AuthorizationService(AuthorizationDao db) {
        this.db = db;
    }

    public List<String> permissions(String username) {
        return db.findPermissions(username);
    }

    public List<String> roles(String username) {
        return db.findRoles(username);
    }

    public void require(String username, String permission) {
        if (!permissions(username).contains(permission))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "没有此操作权限");
    }

    public boolean active(String username) {
        Integer count = db.countActiveUser(username);
        return count != null && count == 1;
    }
}

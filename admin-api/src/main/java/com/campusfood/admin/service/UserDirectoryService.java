package com.campusfood.admin.service;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.campusfood.admin.dao.UserDirectoryDao;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class UserDirectoryService {
    private static final Set<String> ROLES = Set.of("", "user", "merchant", "rider");
    private final AdminService admins;
    private final UserDirectoryDao users;

    private final String appId;

    public UserDirectoryService(AdminService admins, UserDirectoryDao users,
                                @Value("${app.wechat-app-id}") String appId) {
        this.admins = admins;
        this.users = users;
        this.appId = appId;
    }

    public record Page(List<Map<String, Object>> rows, long total, int page, int pageSize) {}

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page list(String authorization, String role, String keyword, int page, int pageSize) {
        admins.requirePermission(authorization, "user.view");
        String search = keyword.strip();
        if (!ROLES.contains(role) || search.length() > 120 || page < 1 || pageSize < 1 || pageSize > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "用户列表筛选或分页参数无效");
        }
        long total = users.count(role, appId, search);
        int currentPage = (int) Math.min(page, Math.max(1, (total - 1) / pageSize + 1));
        List<Map<String, Object>> rows = total == 0 ? List.of()
            : users.list(role, appId, search, pageSize, (long) (currentPage - 1) * pageSize);
        return new Page(rows, total, currentPage, pageSize);
    }
}

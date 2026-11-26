package com.campusfood.admin.service;

import com.campusfood.admin.dao.AdminDao;
import com.campusfood.admin.dao.AdminUserRoleMapper;
import com.campusfood.admin.dao.AuthorizationDao;
import com.campusfood.admin.dto.AdministratorAccessRequest;
import com.campusfood.admin.dto.CreateAdministratorRequest;
import com.campusfood.admin.dto.EncryptedPasswordRequest;
import com.campusfood.admin.dto.ResetPasswordRequest;
import com.campusfood.admin.dto.RoleAccessRequest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** 管理后台账号、角色和授权配置的事务入口。 */
@Service
public class AdministratorAccessService {
    private static final String MANAGE = "admin.manage";
    private static final String SUPER = "super";
    private static final Set<String> BUILT_IN = Set.of(SUPER, "operations", "finance", "readonly");
    private static final Map<String, String> REQUIRED_VIEW = Map.ofEntries(
            Map.entry("merchant.audit", "merchant.view"),
            Map.entry("merchant.manage", "merchant.view"),
            Map.entry("merchant.printer.edit", "merchant.view"),
            Map.entry("product.manage", "merchant.view"),
            Map.entry("rider.audit", "rider.view"),
            Map.entry("rider.manage", "rider.view"),
            Map.entry("order.coordinate", "order.view"),
            Map.entry("refund.manage", "refund.view"),
            Map.entry("withdrawal.review", "withdrawal.view"),
            Map.entry("config.edit", "config.view"),
            Map.entry("wechat.edit", "wechat.view"),
            Map.entry("wechat.toggle", "wechat.view"),
            Map.entry("food-category.edit", "food-category.view"),
            Map.entry("home-banner.edit", "home-banner.view"));

    private final AdminService admins;
    private final AdminDao users;
    private final AuthorizationDao roles;
    private final AdminUserRoleMapper assignments;
    private final PasswordTransportService transport;
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder();

    public AdministratorAccessService(AdminService admins, AdminDao users, AuthorizationDao roles,
            AdminUserRoleMapper assignments, PasswordTransportService transport) {
        this.admins = admins;
        this.users = users;
        this.roles = roles;
        this.assignments = assignments;
        this.transport = transport;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> list(String auth) {
        admins.requirePermission(auth, MANAGE);
        var adminRows = users.listAdministrators();
        var roleRows = roles.listRoles();
        var permissions = roles.listPermissions();
        var userRoles = new HashMap<Long, List<String>>();
        var roleUsers = new HashMap<String, Integer>();
        for (var row : users.listRoleAssignments()) {
            long userId = ((Number) row.get("userId")).longValue();
            String code = (String) row.get("roleCode");
            userRoles.computeIfAbsent(userId, ignored -> new ArrayList<>()).add(code);
            roleUsers.merge(code, 1, Integer::sum);
        }
        var rolePermissions = new HashMap<String, List<String>>();
        for (var row : roles.listRolePermissions()) {
            String permission = (String) row.get("permissionCode");
            if (!permission.equals(MANAGE)) {
                rolePermissions.computeIfAbsent((String) row.get("roleCode"), ignored -> new ArrayList<>()).add(permission);
            }
        }
        for (var row : adminRows) {
            var codes = userRoles.getOrDefault(((Number) row.get("id")).longValue(), List.of());
            row.put("roleCodes", codes);
            row.put("roles", codes.isEmpty() ? null : String.join(",", codes));
        }
        var allCodes = permissions.stream().map(row -> (String) row.get("code")).toList();
        for (var row : roleRows) {
            String code = (String) row.get("code");
            row.put("builtIn", BUILT_IN.contains(code));
            row.put("editable", !SUPER.equals(code));
            row.put("memberCount", roleUsers.getOrDefault(code, 0));
            row.put("permissions", SUPER.equals(code) ? allCodes : rolePermissions.getOrDefault(code, List.of()));
        }
        return Map.of("users", adminRows, "roles", roleRows, "permissions", permissions,
                "requiredView", REQUIRED_VIEW);
    }

    public List<Map<String, Object>> audit(String auth) {
        admins.requirePermission(auth, MANAGE);
        return users.listAdministratorAudit();
    }

    @Transactional
    public Map<String, Object> createAdministrator(EncryptedPasswordRequest encrypted, String auth) {
        String actor = admins.requirePermissionForAccessChange(auth, MANAGE);
        var body = transport.decrypt(encrypted, "/administrators", CreateAdministratorRequest.class);
        String username = Objects.toString(body.username(), "").strip();
        String password = Objects.toString(body.password(), "");
        if (!username.matches("[A-Za-z0-9_.-]{3,64}")) {
            throw error(HttpStatus.BAD_REQUEST, "账号须为 3–64 位字母、数字、点、下划线或短横线");
        }
        var codes = validateRoleCodes(body.roleCodes(), body.role());
        AdminService.validatePassword(password);
        try {
            users.insertUser(username, passwords.encode(password));
        } catch (DuplicateKeyException ex) {
            throw error(HttpStatus.CONFLICT, "账号已存在");
        }
        codes.forEach(code -> users.assignRoleByUsername(code, username));
        users.insertSimpleAudit(actor, "admin.create", username, "CREATED");
        return Map.of("ok", true);
    }

    @Transactional
    public Map<String, Object> updateAdministrator(long id, AdministratorAccessRequest body, String auth) {
        String actor = admins.requirePermissionForAccessChange(auth, MANAGE);
        var codes = validateRoleCodes(body.roleCodes(), body.role());
        if (!(body.enabled() instanceof Boolean enabled)) {
            throw error(HttpStatus.BAD_REQUEST, "启用状态无效");
        }
        var row = administrator(id);
        var originalCodes = assignments.findRoleCodes(id);
        boolean wasEnabled = Boolean.TRUE.equals(row.get("enabled"));
        boolean changedRoles = !codes.equals(originalCodes);
        if (wasEnabled && originalCodes.contains(SUPER) && (!enabled || !codes.contains(SUPER))
                && users.countEnabledSupers() <= 1) {
            throw error(HttpStatus.CONFLICT, "必须保留至少一名启用的超级管理员");
        }
        if (wasEnabled != enabled || changedRoles) {
            users.setAdministratorEnabled(enabled, id);
            if (changedRoles) {
                assignments.deleteRoles(id);
                codes.forEach(code -> users.assignRoleById(id, code));
            }
            users.insertSimpleAudit(actor, "admin.access", row.get("username"), enabled ? "ENABLED" : "DISABLED");
        }
        return Map.of("ok", true);
    }

    @Transactional
    public Map<String, Object> resetPassword(long id, EncryptedPasswordRequest encrypted, String auth) {
        String actor = admins.requirePermissionForAccessChange(auth, MANAGE);
        String target = (String) administrator(id).get("username");
        if (target.equals(actor)) throw error(HttpStatus.BAD_REQUEST, "请通过右上角修改自己的密码");
        var body = transport.decrypt(encrypted, "/administrators/" + id + "/password", ResetPasswordRequest.class);
        String password = Objects.toString(body.password(), "");
        AdminService.validatePassword(password);
        users.resetPasswordById(passwords.encode(password), id);
        users.insertSimpleAudit(actor, "admin.password-reset", target, "RESET");
        return Map.of("ok", true);
    }

    @Transactional
    public Map<String, Object> saveRole(String code, RoleAccessRequest body, String auth) {
        String actor = admins.requirePermissionForAccessChange(auth, MANAGE);
        if (SUPER.equals(code)) throw error(HttpStatus.CONFLICT, "超级管理员角色由系统维护");
        if (code != null) findRole(code);
        String name = Objects.toString(body.name(), "").strip();
        String description = Objects.toString(body.description(), "").strip();
        if (name.isEmpty() || name.length() > 60 || description.length() > 200) {
            throw error(HttpStatus.BAD_REQUEST, "请输入 1–60 个字符的角色名称，说明最多 200 个字符");
        }
        var permissions = validatePermissions(body.permissions());
        boolean creating = code == null;
        String roleCode = creating ? "role-" + UUID.randomUUID().toString().replace("-", "") : code;
        try {
            if (creating) roles.insertRole(roleCode, name, description);
            else roles.updateRole(roleCode, name, description);
        } catch (DuplicateKeyException ex) {
            throw error(HttpStatus.CONFLICT, "角色名称已存在");
        }
        var existing = roles.listRolePermissions().stream().filter(row -> roleCode.equals(row.get("roleCode")))
                .map(row -> (String) row.get("permissionCode")).toList();
        if (!permissions.equals(existing)) {
            if (!creating) roles.clearPermissions(roleCode);
            permissions.forEach(permission -> roles.insertPermission(roleCode, permission));
            if (!creating) roles.revokeSessionsForRole(roleCode);
        }
        users.insertSimpleAudit(actor, creating ? "admin.role.create" : "admin.role.update", name, roleCode);
        return Map.of("ok", true, "code", roleCode);
    }

    @Transactional
    public Map<String, Object> deleteRole(String code, String auth) {
        String actor = admins.requirePermissionForAccessChange(auth, MANAGE);
        var role = findRole(code);
        if (BUILT_IN.contains(code)) throw error(HttpStatus.CONFLICT, "内置角色不可删除");
        if (roles.countAssignedUsers(code) > 0) {
            throw error(HttpStatus.CONFLICT, "请先解除该角色与所有管理员的关联");
        }
        roles.clearPermissions(code);
        roles.deleteRole(code);
        users.insertSimpleAudit(actor, "admin.role.delete", role.get("name"), code);
        return Map.of("ok", true);
    }

    private Map<String, Object> administrator(long id) {
        return users.lockAdministrator(id).stream().findFirst()
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "管理员不存在"));
    }

    private Map<String, Object> findRole(String code) {
        return roles.listRoles().stream().filter(row -> code.equals(row.get("code"))).findFirst()
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "角色不存在"));
    }

    private List<String> validateRoleCodes(List<String> roleCodes, String legacyRole) {
        List<String> codes = roleCodes != null ? roleCodes : legacyRole == null ? List.of() : List.of(legacyRole);
        var available = roles.listRoles().stream().map(row -> (String) row.get("code")).toList();
        if (codes.isEmpty() || codes.size() > available.size() || !available.containsAll(codes)) {
            throw error(HttpStatus.BAD_REQUEST, "请选择有效的管理员角色");
        }
        return codes.stream().distinct().sorted().toList();
    }

    private List<String> validatePermissions(List<String> codes) {
        var available = roles.listPermissions().stream().map(row -> (String) row.get("code"))
                .filter(code -> !MANAGE.equals(code)).toList();
        if (codes == null || codes.isEmpty() || codes.size() > available.size()
                || codes.contains(null) || !available.containsAll(codes)) {
            throw error(HttpStatus.BAD_REQUEST, "请选择有效的角色权限");
        }
        var permissions = new TreeSet<>(codes);
        for (String code : codes) {
            String view = REQUIRED_VIEW.get(code);
            if (view != null) permissions.add(view);
        }
        return List.copyOf(permissions);
    }

    private static ResponseStatusException error(HttpStatus status, String message) {
        return new ResponseStatusException(status, message);
    }
}

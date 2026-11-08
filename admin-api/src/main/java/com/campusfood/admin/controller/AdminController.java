package com.campusfood.admin.controller;

import com.campusfood.admin.dto.*;
import com.campusfood.admin.service.AdminService;

import java.util.Map;

import org.springframework.web.bind.annotation.*;

/** 对外提供管理员登录、业务查询和管理操作接口。 */
@RestController
@RequestMapping("/api/admin/v1")
public class AdminController {
    private final AdminService service;

    public AdminController(AdminService service) {
        this.service = service;
    }

    @PostMapping("/auth/login")
    public Map<String, Object> login(@RequestBody LoginRequest body) {
        return service.login(body);
    }

    @PostMapping("/auth/logout")
    public Map<String, Object> logout(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return service.logout(authorization);
    }

    @PostMapping("/auth/password")
    public Map<String, Object> changePassword(@RequestBody ChangePasswordRequest body,
                                              @RequestHeader(value = "Authorization", required = false) String authorization) {
        return service.changePassword(body, authorization);
    }

    @GetMapping("/workspace")
    public Map<String, Object> workspace(@RequestParam String section,
                                         @RequestHeader(value = "Authorization", required = false) String authorization) {
        return service.workspace(section, authorization);
    }

    @GetMapping("/overview")
    public Map<String, Object> overview(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        return service.overview(authorization);
    }

    @GetMapping("/administrators")
    public Map<String, Object> administrators(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        return service.administrators(authorization);
    }

    @PostMapping("/administrators")
    public Map<String, Object> createAdministrator(@RequestBody CreateAdministratorRequest body,
                                                   @RequestHeader(value = "Authorization", required = false) String authorization) {
        return service.createAdministrator(body, authorization);
    }

    @PutMapping("/administrators/{id}/access")
    public Map<String, Object> changeAdministrator(@PathVariable long id, @RequestBody AdministratorAccessRequest body,
                                                   @RequestHeader(value = "Authorization", required = false) String authorization) {
        return service.changeAdministrator(id, body, authorization);
    }

    @PutMapping("/administrators/{id}/password")
    public Map<String, Object> resetAdministratorPassword(@PathVariable long id, @RequestBody ResetPasswordRequest body,
                                                          @RequestHeader(value = "Authorization", required = false) String authorization) {
        return service.resetAdministratorPassword(id, body, authorization);
    }

    @PostMapping("/actions")
    public Map<String, Object> action(@RequestBody AdminActionRequest body,
                                      @RequestHeader(value = "Authorization", required = false) String authorization) {
        return service.action(body, authorization);
    }
}

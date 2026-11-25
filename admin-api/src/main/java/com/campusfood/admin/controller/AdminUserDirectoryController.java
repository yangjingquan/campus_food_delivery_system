package com.campusfood.admin.controller;

import com.campusfood.admin.service.UserDirectoryService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/v1/users")
public class AdminUserDirectoryController {
    private final UserDirectoryService users;

    public AdminUserDirectoryController(UserDirectoryService users) {
        this.users = users;
    }

    @GetMapping
    public ResponseEntity<UserDirectoryService.Page> list(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(defaultValue = "") String role,
            @RequestParam(defaultValue = "") String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(users.list(authorization, role, keyword, page, pageSize));
    }
}

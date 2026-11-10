package com.campusfood.admin.controller;

import com.campusfood.admin.dto.*;
import com.campusfood.admin.service.AdminService;

import java.time.Duration;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import static com.campusfood.admin.controller.AdminSessionFilter.API_PATH;
import static com.campusfood.admin.controller.AdminSessionFilter.BROWSER_HEADER;
import static com.campusfood.admin.controller.AdminSessionFilter.COOKIE_NAME;

/** 对外提供管理员登录、业务查询和管理操作接口。 */
@RestController
@RequestMapping("/api/admin/v1")
public class AdminController {
    private final AdminService service;
    private final boolean secureSessionCookie;

    public AdminController(AdminService service,
            @Value("${ADMIN_SESSION_COOKIE_SECURE:false}") boolean secureSessionCookie) {
        this.service = service;
        this.secureSessionCookie = secureSessionCookie;
    }

    @PostMapping("/auth/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody LoginRequest body,
            @RequestHeader(value = BROWSER_HEADER, required = false) String browser) {
        var login = service.login(body);
        var response = ResponseEntity.ok().cacheControl(CacheControl.noStore());
        if (!"1".equals(browser)) return response.body(login);
        return response.header(HttpHeaders.SET_COOKIE, sessionCookie((String) login.get("token"), AdminService.SESSION_LIFETIME))
                .body(Map.of("user", login.get("user")));
    }

    @GetMapping("/auth/session")
    public ResponseEntity<Map<String, Object>> session(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.session(authorization));
    }

    @PostMapping("/auth/logout")
    public ResponseEntity<Map<String, Object>> logout(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(value = BROWSER_HEADER, required = false) String browser) {
        if (!"1".equals(browser)) return ResponseEntity.ok(service.logout(authorization));
        try {
            service.logout(authorization);
        } catch (ResponseStatusException exception) {
            if (exception.getStatusCode() != HttpStatus.UNAUTHORIZED) throw exception;
        }
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, sessionCookie("", Duration.ZERO))
                .body(Map.of("ok", true));
    }

    @PostMapping("/auth/password")
    public ResponseEntity<Map<String, Object>> changePassword(@RequestBody ChangePasswordRequest body,
                                              @RequestHeader(value = "Authorization", required = false) String authorization) {
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, sessionCookie("", Duration.ZERO))
                .body(service.changePassword(body, authorization));
    }

    private String sessionCookie(String value, Duration lifetime) {
        return ResponseCookie.from(COOKIE_NAME, value).path(API_PATH).httpOnly(true)
                .secure(secureSessionCookie).sameSite("Strict").maxAge(lifetime).build().toString();
    }

    @GetMapping("/workspace")
    public Map<String, Object> workspace(@RequestParam String section,
                                         @RequestHeader(value = "Authorization", required = false) String authorization) {
        return service.workspace(section, authorization);
    }

    @GetMapping("/merchants/{id}/photo")
    public ResponseEntity<byte[]> merchantPhoto(@PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        var photo = service.merchantPhoto(id, authorization);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType((String) photo.get("media_type")))
                .body((byte[]) photo.get("image_data"));
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

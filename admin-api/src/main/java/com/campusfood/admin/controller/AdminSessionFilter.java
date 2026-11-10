package com.campusfood.admin.controller;

import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** 将浏览器会话 Cookie 接入现有的管理员身份校验。 */
@Component
public class AdminSessionFilter extends OncePerRequestFilter {
    static final String COOKIE_NAME = "campus_admin_session";
    static final String API_PATH = "/api/admin/v1";
    static final String BROWSER_HEADER = "X-Admin-Session";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getServletPath().startsWith(API_PATH + "/")
                || !"1".equals(request.getHeader(BROWSER_HEADER))
                || request.getHeader(HttpHeaders.AUTHORIZATION) != null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // 自定义请求头让普通跨站表单无法使用 Cookie 执行管理员操作。
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (COOKIE_NAME.equals(cookie.getName())) {
                    String authorization = "Bearer " + cookie.getValue();
                    chain.doFilter(new HttpServletRequestWrapper(request) {
                        @Override
                        public String getHeader(String name) {
                            return HttpHeaders.AUTHORIZATION.equalsIgnoreCase(name)
                                    ? authorization : super.getHeader(name);
                        }

                        @Override
                        public Enumeration<String> getHeaders(String name) {
                            return HttpHeaders.AUTHORIZATION.equalsIgnoreCase(name)
                                    ? Collections.enumeration(Collections.singleton(authorization)) : super.getHeaders(name);
                        }
                    }, response);
                    return;
                }
            }
        }
        chain.doFilter(request, response);
    }
}

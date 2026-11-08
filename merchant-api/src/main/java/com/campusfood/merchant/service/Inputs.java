package com.campusfood.merchant.service;

import java.util.Map;
import java.util.Objects;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

final class Inputs {
    private Inputs() {
    }

    static String text(Map<String, Object> body, String key, String label, int max, boolean required) {
        String value = Objects.toString(body.get(key), "").trim();
        if ((required && value.isEmpty()) || value.length() > max) {
            throw invalid(label + "无效，最多 " + max + " 个字符");
        }
        return value;
    }

    static long integer(Map<String, Object> body, String key, String label, long min, long max) {
        try {
            long value = Long.parseLong(Objects.toString(body.get(key), ""));
            if (value >= min && value <= max) {
                return value;
            }
        } catch (NumberFormatException ignored) {
        }
        throw invalid(label + "须为 " + min + " 至 " + max + " 的整数");
    }

    static boolean flag(Map<String, Object> body, String key, String label) {
        if (body.get(key) instanceof Boolean value) {
            return value;
        }
        throw invalid(label + "无效");
    }

    static ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}

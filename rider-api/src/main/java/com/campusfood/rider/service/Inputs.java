package com.campusfood.rider.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class Inputs {
    private Inputs() {
    }

    static String required(String value, int max, String label) {
        String text = value == null ? "" : value.trim();
        if (text.isEmpty() || text.length() > max) {
            throw invalid("请检查" + label);
        }
        return text;
    }

    static ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}

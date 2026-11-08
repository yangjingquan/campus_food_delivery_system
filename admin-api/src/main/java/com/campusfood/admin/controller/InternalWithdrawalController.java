package com.campusfood.admin.controller;

import com.campusfood.admin.service.WithdrawalService;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** 提供受内部密钥保护的提现及渠道结果接口。 */
@RestController
@RequestMapping("/internal/v1")
public class InternalWithdrawalController {
    private final WithdrawalService withdrawals;
    @Value("${app.internal-api-key:}")
    private String internalApiKey;

    public InternalWithdrawalController(WithdrawalService withdrawals) {
        this.withdrawals = withdrawals;
    }

    /** 内部密钥未配置时关闭接口；使用固定时间比较校验调用方。 */
    private void authorize(String key) {
        if (internalApiKey.isBlank())
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "内部服务认证尚未配置");
        if (key == null || !MessageDigest.isEqual(internalApiKey.getBytes(StandardCharsets.UTF_8),
                key.getBytes(StandardCharsets.UTF_8)))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "内部服务认证失败");
    }

    @PostMapping("/withdrawals")
    public Map<String, Object> request(@RequestHeader(value = "X-Internal-Key", required = false) String key,
                                       @RequestBody Map<String, Object> body) {
        authorize(key);
        return withdrawals.request(body);
    }

    @PostMapping("/withdrawals/{id}/transfer/prepare")
    public Map<String, Object> prepare(@RequestHeader(value = "X-Internal-Key", required = false) String key,
                                       @PathVariable String id) {
        authorize(key);
        return withdrawals.prepareTransfer(id);
    }

    // 此接口接收可信内部服务核验后的结果，并不是微信原始回调入口。
    @PostMapping("/withdrawals/{id}/transfer/result")
    public Map<String, Object> result(@RequestHeader(value = "X-Internal-Key", required = false) String key,
                                      @PathVariable String id, @RequestBody Map<String, Object> body) {
        authorize(key);
        return withdrawals.transferResult(id, body);
    }
}

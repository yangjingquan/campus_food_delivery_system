package com.campusfood.merchant.service;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@Service
public class DomainClient {
    private static final ParameterizedTypeReference<Map<String, Object>> RESPONSE = new ParameterizedTypeReference<>() {};

    private final RestClient user;
    private final RestClient admin;
    private final ObjectMapper json;

    public DomainClient(ObjectMapper json, @Value("${app.user-api-url}") String userUrl,
                        @Value("${app.user-api-key}") String userKey,
                        @Value("${app.admin-api-url}") String adminUrl,
                        @Value("${app.admin-api-key}") String adminKey) {
        this.json = json;
        this.user = client(userUrl, userKey);
        this.admin = client(adminUrl, adminKey);
    }

    private static RestClient client(String baseUrl, String key) {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3)).build());
        factory.setReadTimeout(Duration.ofSeconds(8));
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory)
            .defaultHeader("X-Internal-Key", key).build();
    }

    public Map<String, Object> orderAction(String merchant, String order, String action) {
        return call(user.post().uri("/internal/v1/merchants/{merchant}/orders/{order}/status", merchant, order)
            .body(Map.of("action", action)));
    }

    public Map<String, Object> location(String merchant, String order) {
        return call(user.get().uri("/internal/v1/merchants/{merchant}/orders/{order}/location", merchant, order));
    }

    public Map<String, Object> refund(String merchant, String order, Map<String, Object> body) {
        return call(user.post().uri("/internal/v1/merchants/{merchant}/orders/{order}/refund", merchant, order).body(body));
    }

    public Map<String, Object> withdraw(String accountId, Map<String, Object> body) {
        long amount = Inputs.integer(body, "amountCents", "提现金额（分）", 1, 100_000_000_000L);
        String requestKey = Inputs.text(body, "requestKey", "提现申请编号", 64, true);
        if (!requestKey.matches("[A-Za-z0-9_-]{8,64}")) throw Inputs.invalid("提现申请编号无效");
        // 服务端指定资金账户并给幂等键加店铺作用域，客户端无法指定其他账户。
        return call(admin.post().uri("/internal/v1/withdrawals")
            .body(Map.of("accountId", accountId, "requestKey", accountId + "-" + requestKey, "amountCents", amount)));
    }

    private Map<String, Object> call(RestClient.RequestHeadersSpec<?> request) {
        try {
            return request.retrieve().body(RESPONSE);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 401) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "内部服务认证未就绪");
            }
            String message = "业务服务暂不可用";
            try {
                var body = json.readTree(e.getResponseBodyAsString());
                if (body != null && body.path("message").isString()) message = body.path("message").asText();
            } catch (RuntimeException ignored) {
            }
            throw new ResponseStatusException(e.getStatusCode(), message);
        } catch (RestClientException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "业务服务未响应，请先刷新记录确认结果");
        }
    }
}

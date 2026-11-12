package com.campusfood.rider.service;

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

    public Map<String, Object> pickup(String rider, String token) {
        return call(user.post().uri("/internal/v1/riders/{rider}/pickup", rider).body(Map.of("token", token)));
    }

    public Map<String, Object> complete(String rider, String order) {
        return call(user.post().uri("/internal/v1/riders/{rider}/orders/{order}/complete", rider, order));
    }

    public Map<String, Object> location(String rider, String order, Map<String, Object> body) {
        return call(user.post().uri("/internal/v1/riders/{rider}/orders/{order}/location", rider, order).body(body));
    }

    public Map<String, Object> withdraw(String rider, long amountCents, String requestKey) {
        if (amountCents <= 0 || amountCents > 100_000_000_000L || requestKey == null
                || !requestKey.matches("[A-Za-z0-9_-]{8,64}")) {
            throw Inputs.invalid("提现金额或申请编号无效");
        }
        return call(admin.post().uri("/internal/v1/withdrawals").body(Map.of(
            "accountId", rider, "requestKey", rider + "-" + requestKey, "amountCents", amountCents
        )));
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

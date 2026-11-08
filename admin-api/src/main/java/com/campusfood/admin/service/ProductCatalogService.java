package com.campusfood.admin.service;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

import com.campusfood.admin.dto.ProductPhoto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

/** 将已鉴权的管理员商品请求转发到商品数据所属服务。 */
@Service
public class ProductCatalogService {
    private static final ParameterizedTypeReference<Map<String, Object>> CATALOG_RESPONSE =
            new ParameterizedTypeReference<>() {};

    private final AdminService admins;
    private final RestClient merchantApi;

    public ProductCatalogService(AdminService admins,
            @Value("${app.merchant-api-url:http://localhost:8083}") String merchantApiUrl,
            @Value("${app.internal-api-key:}") String internalApiKey) {
        this.admins = admins;
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3)).build());
        factory.setReadTimeout(Duration.ofSeconds(8));
        this.merchantApi = RestClient.builder().baseUrl(merchantApiUrl).requestFactory(factory)
                .defaultHeader("X-Internal-Key", internalApiKey).build();
    }

    public Map<String, Object> catalog(String authorization, String keyword, String merchantId,
                                       String availability, int page, int pageSize) {
        admins.requirePermission(authorization, "merchant.view");
        try {
            return merchantApi.get().uri(uri -> uri.path("/internal/v1/admin/products")
                    .queryParam("keyword", keyword == null ? "" : keyword)
                    .queryParam("merchantId", merchantId == null ? "" : merchantId)
                    .queryParam("availability", availability == null ? "" : availability)
                    .queryParam("page", page).queryParam("pageSize", pageSize).build())
                    .retrieve().body(CATALOG_RESPONSE);
        } catch (RestClientResponseException exception) {
            throw serviceError(exception, false);
        } catch (RestClientException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "商品数据服务未响应");
        }
    }

    public ProductPhoto photo(String authorization, String productId) {
        admins.requirePermission(authorization, "merchant.view");
        try {
            var response = merchantApi.get().uri("/internal/v1/admin/products/{id}/photo", productId)
                    .retrieve().toEntity(byte[].class);
            String mediaType = response.getHeaders().getContentType() == null ? "application/octet-stream"
                    : response.getHeaders().getContentType().toString();
            return new ProductPhoto(response.getBody() == null ? new byte[0] : response.getBody(), mediaType);
        } catch (RestClientResponseException exception) {
            throw serviceError(exception, true);
        } catch (RestClientException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "商品图片暂不可用");
        }
    }

    private ResponseStatusException serviceError(RestClientResponseException exception, boolean photo) {
        int status = exception.getStatusCode().value();
        if (photo && status == 404) {
            return new ResponseStatusException(HttpStatus.NOT_FOUND, "商品图片不存在");
        }
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                photo ? "商品图片读取失败" : "商品目录读取失败");
    }
}

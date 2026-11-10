package com.campusfood.admin.service;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.campusfood.admin.dto.FoodCategoryRequest;
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
import tools.jackson.databind.JsonNode;

/** 将已鉴权的管理员商品请求转发到商品数据所属服务。 */
@Service
public class ProductCatalogService {
    private static final String CATEGORIES_PATH = "/internal/v1/admin/products/categories";
    private static final ParameterizedTypeReference<List<Map<String, Object>>> CATEGORY_LIST =
            new ParameterizedTypeReference<>() {};
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

    public List<Map<String, Object>> categories(String authorization) {
        admins.requirePermission(authorization, "food-category.view");
        try {
            return merchantApi.get().uri(CATEGORIES_PATH).retrieve().body(CATEGORY_LIST);
        } catch (RestClientException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "食品分类暂不可用");
        }
    }

    public void createCategory(String authorization, FoodCategoryRequest body) {
        admins.requirePermission(authorization, "food-category.edit");
        changeCategory(merchantApi.post().uri(CATEGORIES_PATH).body(body));
    }

    public void renameCategory(String authorization, long id, FoodCategoryRequest body) {
        admins.requirePermission(authorization, "food-category.edit");
        changeCategory(merchantApi.put().uri(CATEGORIES_PATH + "/{id}", id).body(body));
    }

    public void deleteCategory(String authorization, long id) {
        admins.requirePermission(authorization, "food-category.edit");
        changeCategory(merchantApi.delete().uri(CATEGORIES_PATH + "/{id}", id));
    }

    private void changeCategory(RestClient.RequestHeadersSpec<?> request) {
        try {
            request.retrieve().toBodilessEntity();
        } catch (RestClientResponseException exception) {
            int status = exception.getStatusCode().value();
            if (status == 400 || status == 404 || status == 409) {
                String message = "食品分类操作失败，请刷新后重试";
                try {
                    JsonNode response = exception.getResponseBodyAs(JsonNode.class);
                    if (response != null) message = response.path("message").asText(message);
                } catch (RestClientException ignored) {
                    // 旧版商户服务可能没有统一错误响应。
                }
                throw new ResponseStatusException(exception.getStatusCode(), message);
            }
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "食品分类保存失败");
        } catch (RestClientException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "食品分类暂不可用");
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

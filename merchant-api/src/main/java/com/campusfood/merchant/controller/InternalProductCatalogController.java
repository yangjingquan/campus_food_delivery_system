package com.campusfood.merchant.controller;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

import com.campusfood.merchant.service.ProductService;
import com.campusfood.merchant.service.UploadedPhoto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** 管理服务专用的商品只读接口，不经公网网关开放。 */
@RestController
@RequestMapping("/internal/v1/admin/products")
public class InternalProductCatalogController {
    private final ProductService products;

    @Value("${app.internal-api-key:}")
    private String internalApiKey;

    public InternalProductCatalogController(ProductService products) {
        this.products = products;
    }

    /** 未配置内部密钥时关闭接口，并使用固定时间比较密钥。 */
    private void authorize(String key) {
        if (internalApiKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "内部服务认证尚未配置");
        }
        if (key == null || !MessageDigest.isEqual(internalApiKey.getBytes(StandardCharsets.UTF_8),
                key.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "内部服务认证失败");
        }
    }

    @GetMapping
    public Map<String, Object> catalog(@RequestHeader(value = "X-Internal-Key", required = false) String key,
            @RequestParam(defaultValue = "") String keyword,
            @RequestParam(defaultValue = "") String merchantId,
            @RequestParam(defaultValue = "") String availability,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        authorize(key);
        return products.adminCatalog(keyword, merchantId, availability, page, pageSize);
    }

    @GetMapping("/{id}/photo")
    public ResponseEntity<byte[]> photo(@RequestHeader(value = "X-Internal-Key", required = false) String key,
            @PathVariable String id) {
        authorize(key);
        UploadedPhoto photo = products.adminPhoto(id);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType(photo.mediaType())).body(photo.data());
    }
}

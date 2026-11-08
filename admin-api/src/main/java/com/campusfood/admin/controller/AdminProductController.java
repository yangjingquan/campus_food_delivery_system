package com.campusfood.admin.controller;

import java.util.Map;

import com.campusfood.admin.dto.ProductPhoto;
import com.campusfood.admin.service.ProductCatalogService;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 管理员端商户商品只读接口。 */
@RestController
@RequestMapping("/api/admin/v1/products")
public class AdminProductController {
    private final ProductCatalogService products;

    public AdminProductController(ProductCatalogService products) {
        this.products = products;
    }

    @GetMapping
    public Map<String, Object> catalog(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(defaultValue = "") String keyword,
            @RequestParam(defaultValue = "") String merchantId,
            @RequestParam(defaultValue = "") String availability,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return products.catalog(authorization, keyword, merchantId, availability, page, pageSize);
    }

    @GetMapping("/{id}/photo")
    public ResponseEntity<byte[]> photo(@PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        ProductPhoto photo = products.photo(authorization, id);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType(photo.mediaType())).body(photo.data());
    }
}

package com.campusfood.admin.controller;

import java.util.List;
import java.util.Map;

import com.campusfood.admin.dto.FoodCategoryRequest;
import com.campusfood.admin.service.ProductCatalogService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 管理员端一级食品分类接口。 */
@RestController
@RequestMapping("/api/admin/v1/food-categories")
public class AdminFoodCategoryController {
    private final ProductCatalogService products;

    public AdminFoodCategoryController(ProductCatalogService products) {
        this.products = products;
    }

    @GetMapping
    public List<Map<String, Object>> list(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        return products.categories(authorization);
    }

    @PostMapping
    public ResponseEntity<Void> create(@RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody FoodCategoryRequest body) {
        products.createCategory(authorization, body);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}")
    public ResponseEntity<Void> rename(@RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable long id, @RequestBody FoodCategoryRequest body) {
        products.renameCategory(authorization, id, body);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable long id) {
        products.deleteCategory(authorization, id);
        return ResponseEntity.noContent().build();
    }
}

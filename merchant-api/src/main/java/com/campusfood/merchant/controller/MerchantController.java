package com.campusfood.merchant.controller;

import java.util.List;
import java.util.Map;

import com.campusfood.merchant.service.DomainClient;
import com.campusfood.merchant.service.FinanceService;
import com.campusfood.merchant.service.OrderService;
import com.campusfood.merchant.service.ProductService;
import com.campusfood.merchant.service.SessionService;
import com.campusfood.merchant.service.StoreService;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/merchant/v1")
public class MerchantController {
    private static final String AUTH = "Authorization";

    private final SessionService sessions;
    private final StoreService stores;
    private final ProductService products;
    private final OrderService orders;
    private final FinanceService finance;
    private final DomainClient domain;

    public MerchantController(SessionService sessions, StoreService stores, ProductService products,
                              OrderService orders, FinanceService finance, DomainClient domain) {
        this.sessions = sessions;
        this.stores = stores;
        this.products = products;
        this.orders = orders;
        this.finance = finance;
        this.domain = domain;
    }

    @PostMapping("/auth/login")
    public Map<String, Object> login(@RequestBody Map<String, Object> body) {
        return sessions.login(body);
    }

    @PostMapping("/auth/logout")
    public ResponseEntity<Void> logout(@RequestHeader(value = AUTH, required = false) String auth) {
        sessions.logout(auth);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/profile")
    public Map<String, Object> profile(@RequestHeader(value = AUTH, required = false) String auth) {
        return stores.profile(sessions.require(auth));
    }

    @PostMapping("/profile")
    public ResponseEntity<Void> submitProfile(@RequestHeader(value = AUTH, required = false) String auth, @RequestBody Map<String, Object> body) {
        stores.submit(sessions.require(auth), body);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/profile/photo")
    public ResponseEntity<byte[]> photo(@RequestHeader(value = AUTH, required = false) String auth) {
        var photo = stores.storePhoto(sessions.require(auth));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .contentType(MediaType.parseMediaType(photo.mediaType())).body(photo.data());
    }

    @PutMapping("/profile/business-status")
    public ResponseEntity<Void> businessStatus(@RequestHeader(value = AUTH, required = false) String auth, @RequestBody Map<String, Object> body) {
        stores.businessStatus(store(auth), body);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/products")
    public List<Map<String, Object>> products(@RequestHeader(value = AUTH, required = false) String auth) {
        return products.list(store(auth));
    }

    @PostMapping("/products")
    public Map<String, String> createProduct(@RequestHeader(value = AUTH, required = false) String auth, @RequestBody Map<String, Object> body) {
        return products.save(store(auth), null, body);
    }

    @PutMapping("/products/{id}")
    public Map<String, String> editProduct(@RequestHeader(value = AUTH, required = false) String auth, @PathVariable String id,
                                          @RequestBody Map<String, Object> body) {
        return products.save(store(auth), id, body);
    }

    @PutMapping("/products/{id}/availability")
    public ResponseEntity<Void> availability(@RequestHeader(value = AUTH, required = false) String auth, @PathVariable String id,
                                              @RequestBody Map<String, Object> body) {
        products.availability(store(auth), id, body);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/products/{id}/photo")
    public ResponseEntity<byte[]> productPhoto(@RequestHeader(value = AUTH, required = false) String auth,
                                               @PathVariable String id) {
        var photo = products.photo(store(auth), id);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .contentType(MediaType.parseMediaType(photo.mediaType())).body(photo.data());
    }

    @GetMapping("/products/{id}/stock-events")
    public List<Map<String, Object>> stockHistory(@RequestHeader(value = AUTH, required = false) String auth, @PathVariable String id) {
        return products.history(store(auth), id);
    }

    @GetMapping("/orders")
    public Map<String, Object> orders(@RequestHeader(value = AUTH, required = false) String auth, @RequestParam(defaultValue = "") String status,
                                      @RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "0") int page) {
        return orders.list(store(auth), status, q, page);
    }

    @GetMapping("/orders/{id}")
    public Map<String, Object> order(@RequestHeader(value = AUTH, required = false) String auth, @PathVariable String id) {
        return orders.detail(store(auth), id);
    }

    @PostMapping("/orders/{id}/status")
    public Map<String, Object> orderAction(@RequestHeader(value = AUTH, required = false) String auth, @PathVariable String id,
                                           @RequestBody Map<String, String> body) {
        return domain.orderAction(store(auth), id, body.getOrDefault("action", ""));
    }

    @PostMapping("/orders/{id}/ticket")
    public Map<String, Object> ticket(@RequestHeader(value = AUTH, required = false) String auth, @PathVariable String id) {
        return orders.ticket(store(auth), id);
    }

    @GetMapping("/orders/{id}/location")
    public Map<String, Object> location(@RequestHeader(value = AUTH, required = false) String auth, @PathVariable String id) {
        return domain.location(store(auth), id);
    }

    @PostMapping("/orders/{id}/refund")
    public Map<String, Object> refund(@RequestHeader(value = AUTH, required = false) String auth, @PathVariable String id,
                                      @RequestBody Map<String, Object> body) {
        return domain.refund(store(auth), id, body);
    }

    @GetMapping("/aftersales")
    public List<Map<String, Object>> aftersales(@RequestHeader(value = AUTH, required = false) String auth, @RequestParam(defaultValue = "0") int page) {
        return orders.aftersales(store(auth), page);
    }

    @GetMapping("/finance")
    public Map<String, Object> finance(@RequestHeader(value = AUTH, required = false) String auth, @RequestParam(defaultValue = "1") int days) {
        return finance.summary(store(auth), days);
    }

    @GetMapping("/finance/{kind}")
    public List<Map<String, Object>> financeRecords(@RequestHeader(value = AUTH, required = false) String auth, @PathVariable String kind) {
        return finance.records(store(auth), kind);
    }

    @PostMapping("/finance/withdrawals")
    public Map<String, Object> withdraw(@RequestHeader(value = AUTH, required = false) String auth, @RequestBody Map<String, Object> body) {
        return domain.withdraw(store(auth), body);
    }

    private String store(String auth) {
        return sessions.require(auth).approvedStore();
    }
}

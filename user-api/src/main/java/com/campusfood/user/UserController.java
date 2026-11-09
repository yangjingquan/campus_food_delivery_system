package com.campusfood.user;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/user/v1")
public class UserController {
    private final AuthService auth;
    private final ProfileService profiles;
    private final CatalogService catalog;
    private final AddressService addresses;
    private final OrderService orders;
    private final RefundService refunds;
    private final DeliveryService delivery;

    public UserController(AuthService auth, CatalogService catalog, AddressService addresses, OrderService orders, RefundService refunds, DeliveryService delivery, ProfileService profiles) {
        this.auth = auth; this.catalog = catalog; this.addresses = addresses; this.orders = orders; this.refunds = refunds; this.delivery = delivery;
        this.profiles = profiles;
    }

    @PostMapping("/auth/wechat")
    public Map<String, Object> login(@RequestBody Map<String, Object> body) { return auth.login(String.valueOf(body.getOrDefault("code", ""))); }
    @PostMapping("/auth/logout")
    public Map<String, Object> logout(HttpServletRequest request) { auth.logout(request); return Map.of("ok", true); }
    @GetMapping("/me")
    public ProfileService.Profile me(HttpServletRequest request) {
        return profiles.get(auth.require(request).userId());
    }

    @PostMapping(path = "/me", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ProfileService.Profile updateProfile(HttpServletRequest request, @RequestParam(required = false) String displayName,
            @RequestParam(required = false) MultipartFile avatar) {
        return profiles.update(auth.require(request).userId(), displayName, avatar);
    }

    @PutMapping(path = "/me", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ProfileService.Profile updateNickname(HttpServletRequest request, @RequestBody Nickname body) {
        return profiles.update(auth.require(request).userId(), body.displayName(), null);
    }

    public record Nickname(String displayName) {}

    @GetMapping("/me/avatar")
    public ResponseEntity<byte[]> avatar(HttpServletRequest request) {
        var avatar = profiles.avatar(auth.require(request).userId());
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(avatar.mediaType()))
            .cacheControl(CacheControl.noStore())
            .header("X-Content-Type-Options", "nosniff")
            .body(avatar.data());
    }

    @GetMapping("/config")
    public Map<String, Object> config() { return catalog.config(); }
    @GetMapping("/catalog")
    public Map<String, Object> catalog(@RequestParam(defaultValue = "") String keyword, @RequestParam(defaultValue = "全部") String category,
            @RequestParam(defaultValue = "") String merchantId) {
        return Map.of("merchants", catalog.catalog(keyword, category, merchantId));
    }

    @GetMapping("/catalog/{kind:merchants|products}/{id}/photo")
    public ResponseEntity<byte[]> catalogPhoto(@PathVariable String kind, @PathVariable String id) {
        var photo = catalog.photo("products".equals(kind), id);
        return ResponseEntity.ok()
            .contentType(org.springframework.http.MediaType.parseMediaType(photo.mediaType()))
            .cacheControl(org.springframework.http.CacheControl.maxAge(java.time.Duration.ofMinutes(5)).cachePublic())
            .body(photo.bytes());
    }

    @GetMapping("/addresses")
    public Map<String, Object> addresses(HttpServletRequest request) { return Map.of("rows", addresses.list(auth.require(request).userId())); }
    @PostMapping("/addresses")
    public Map<String, Object> createAddress(@RequestBody Map<String, Object> body, HttpServletRequest request) { return addresses.create(auth.require(request).userId(), body); }
    @PutMapping("/addresses/{id}")
    public Map<String, Object> updateAddress(@PathVariable String id, @RequestBody Map<String, Object> body, HttpServletRequest request) { return addresses.update(auth.require(request).userId(), id, body); }
    @DeleteMapping("/addresses/{id}")
    public Map<String, Object> deleteAddress(@PathVariable String id, HttpServletRequest request) { addresses.delete(auth.require(request).userId(), id); return Map.of("ok", true); }

    @PostMapping("/checkout")
    public Map<String, Object> checkout(@RequestBody Map<String, Object> body, HttpServletRequest request) { return orders.checkout(auth.require(request).userId(), body); }
    @PostMapping("/payments/{paymentId}/mock-success")
    public Map<String, Object> mockPayment(@PathVariable String paymentId, HttpServletRequest request) { return orders.mockPayment(auth.require(request).userId(), paymentId); }
    @PostMapping("/payments/{paymentId}/prepare")
    public Map<String, String> preparePayment(@PathVariable String paymentId, HttpServletRequest request) {
        long userId = auth.require(request).userId();
        orders.beginWechatPayment(userId, paymentId);
        return orders.prepareWechatPayment(userId, paymentId);
    }

    @PostMapping("/payments/{paymentId}/sync")
    public Map<String, Object> syncPayment(@PathVariable String paymentId, HttpServletRequest request) {
        return orders.syncPayment(auth.require(request).userId(), paymentId);
    }

    @PostMapping("/payments/wechat/notify")
    public ResponseEntity<Void> wechatPaymentNotification(
            @RequestHeader("Wechatpay-Timestamp") String timestamp, @RequestHeader("Wechatpay-Nonce") String nonce,
            @RequestHeader("Wechatpay-Serial") String serial, @RequestHeader("Wechatpay-Signature") String signature,
            @RequestBody String body) {
        orders.wechatNotification(timestamp, nonce, serial, signature, body);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/payments/{paymentId}")
    public Map<String, Object> payment(@PathVariable String paymentId, HttpServletRequest request) { return orders.payment(auth.require(request).userId(), paymentId); }
    @GetMapping("/orders")
    public Map<String, Object> orderList(@RequestParam(defaultValue = "全部") String status, HttpServletRequest request) { return Map.of("rows", orders.list(auth.require(request).userId(), status)); }
    @GetMapping("/orders/{orderId}")
    public Map<String, Object> order(@PathVariable String orderId, HttpServletRequest request) { return orders.detail(auth.require(request).userId(), orderId); }
    @GetMapping("/orders/{orderId}/location")
    public Map<String, Object> location(@PathVariable String orderId, HttpServletRequest request) { return delivery.userLocation(auth.require(request).userId(), orderId); }
    @PostMapping("/orders/{orderId}/aftersales")
    public Map<String, Object> requestRefund(@PathVariable String orderId, @RequestBody Map<String, Object> body, HttpServletRequest request) { return refunds.apply(auth.require(request).userId(), orderId, body); }
    @GetMapping("/aftersales/{refundId}")
    public Map<String, Object> afterSale(@PathVariable String refundId, HttpServletRequest request) { return refunds.detail(auth.require(request).userId(), refundId); }
    @PostMapping("/aftersales/{refundId}/intervene")
    public Map<String, Object> intervene(@PathVariable String refundId, HttpServletRequest request) { return refunds.intervene(auth.require(request).userId(), refundId); }
    @PostMapping("/aftersales/{refundId}/close")
    public Map<String, Object> closeAfterSale(@PathVariable String refundId, HttpServletRequest request) { return refunds.endAfterSale(auth.require(request).userId(), refundId); }
}

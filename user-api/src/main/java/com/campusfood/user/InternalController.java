package com.campusfood.user;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/v1")
public class InternalController {
    private final DeliveryService delivery;
    private final RefundService refunds;
    private final StoreOrderingCode orderingCodes;

    public InternalController(DeliveryService delivery, RefundService refunds, StoreOrderingCode orderingCodes) {
        this.delivery = delivery;
        this.refunds = refunds;
        this.orderingCodes = orderingCodes;
    }

    @GetMapping("/merchants/{merchant}/ordering-code")
    public Map<String, String> orderingCode(@PathVariable String merchant, HttpServletRequest request) {
        delivery.requireInternal(request);
        return orderingCodes.image(merchant);
    }

    @PostMapping("/merchants/{merchant}/orders/{orderId}/status")
    public Map<String, Object> merchantStatus(@PathVariable String merchant, @PathVariable String orderId,
            @RequestBody Map<String, String> body, HttpServletRequest request) {
        delivery.requireInternal(request);
        return delivery.merchantStatus(merchant, orderId, body.getOrDefault("action", ""));
    }

    @GetMapping("/merchants/{merchant}/orders/{orderId}/location")
    public Map<String, Object> merchantLocation(@PathVariable String merchant, @PathVariable String orderId,
            HttpServletRequest request) {
        delivery.requireInternal(request);
        return delivery.merchantLocation(merchant, orderId);
    }

    @PostMapping("/merchants/{merchant}/orders/{orderId}/refund")
    public Map<String, Object> merchantRefund(@PathVariable String merchant, @PathVariable String orderId,
            @RequestBody Map<String, Object> body, HttpServletRequest request) {
        delivery.requireInternal(request);
        return refunds.merchantOperation(merchant, orderId, body);
    }

    // 骑手服务鉴权后转发骑手编号，接口不经过小程序网关。
    @PostMapping("/delivery/pickup")
    public Map<String, Object> pickup(@RequestBody Map<String, String> body, HttpServletRequest request) {
        delivery.requireInternal(request);
        return delivery.pickup(body.get("token"), body.get("riderId"));
    }

    @PostMapping("/riders/{riderId}/pickup")
    public Map<String, Object> riderPickup(@PathVariable String riderId, @RequestBody Map<String, String> body,
                                           HttpServletRequest request) {
        delivery.requireInternal(request);
        return delivery.pickup(body.get("token"), riderId, true);
    }

    @PostMapping(value = "/riders/{riderId}/orders/{orderId}/complete", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public Map<String, Object> riderComplete(@PathVariable String riderId, @PathVariable String orderId,
                                             HttpServletRequest request) throws IOException {
        delivery.requireInternal(request);
        byte[] photo = request.getInputStream().readNBytes(DeliveryProof.MAX_BYTES + 1);
        return delivery.riderComplete(riderId, orderId, photo);
    }

    @PostMapping("/riders/{riderId}/orders/{orderId}/location")
    public Map<String, Object> riderLocation(@PathVariable String riderId, @PathVariable String orderId,
                                             @RequestBody Map<String, Object> body, HttpServletRequest request) {
        delivery.requireInternal(request);
        return delivery.riderLocation(riderId, orderId, body);
    }

    @PostMapping("/orders/{orderId}/status")
    public Map<String, Object> updateStatus(@PathVariable String orderId, @RequestBody Map<String, Object> body, HttpServletRequest request) {
        delivery.requireInternal(request);
        return delivery.updateStatus(orderId, body);
    }

    @PostMapping("/orders/{orderId}/location")
    public Map<String, Object> updateLocation(@PathVariable String orderId, @RequestBody Map<String, Object> body, HttpServletRequest request) {
        delivery.requireInternal(request);
        return delivery.updateLocation(orderId, body);
    }

    @PostMapping("/aftersales/{refundId}/decision")
    public Map<String, Object> refundDecision(@PathVariable String refundId, @RequestBody Map<String, Object> body, HttpServletRequest request) {
        delivery.requireInternal(request);
        return refunds.merchantDecisionInternal(refundId, body);
    }

    @PostMapping("/aftersales/{refundId}/result")
    public Map<String, Object> refundResult(@PathVariable String refundId, @RequestBody Map<String, Object> body, HttpServletRequest request) {
        delivery.requireInternal(request);
        return refunds.internalResult(refundId, String.valueOf(body.getOrDefault("outcome", "")), String.valueOf(body.getOrDefault("reason", "")));
    }

    @PostMapping("/aftersales/{refundId}/offline-confirm")
    public Map<String, Object> offlineConfirm(@PathVariable String refundId, @RequestBody Map<String, Object> body, HttpServletRequest request) {
        delivery.requireInternal(request);
        return refunds.confirmOffline(refundId, body);
    }
}

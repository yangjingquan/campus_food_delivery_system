package com.campusfood.user;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
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
    public InternalController(DeliveryService delivery, RefundService refunds) { this.delivery = delivery; this.refunds = refunds; }

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

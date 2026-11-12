package com.campusfood.rider.controller;

import com.campusfood.rider.service.DomainClient;
import com.campusfood.rider.service.RiderFinance;
import com.campusfood.rider.service.RiderIdentityService;
import com.campusfood.rider.service.RiderOrders;
import com.campusfood.rider.service.RiderReminders;
import java.io.IOException;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/rider/v1")
public class RiderController {
    private final RiderIdentityService identities;
    private final RiderOrders orders;
    private final RiderFinance finance;
    private final DomainClient domain;
    private final RiderReminders reminders;

    public RiderController(RiderIdentityService identities, RiderOrders orders, RiderFinance finance,
                           DomainClient domain, RiderReminders reminders) {
        this.identities = identities;
        this.orders = orders;
        this.finance = finance;
        this.domain = domain;
        this.reminders = reminders;
    }

    @PostMapping("/auth/login")
    public Object login(@RequestBody LoginRequest body) {
        return identities.login(body.code());
    }

    @PostMapping("/auth/logout")
    public Map<String, Boolean> logout(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth) {
        identities.logout(auth);
        return Map.of("ok", true);
    }

    @GetMapping("/profile")
    public Object profile(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth) {
        return identities.profile(identities.authenticate(auth));
    }

    @PostMapping("/profile/application")
    public Object apply(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
                        @RequestParam String name, @RequestParam String phone, @RequestParam String gender,
                        @RequestParam String availableTime, @RequestParam MultipartFile studentCard) throws IOException {
        return identities.apply(identities.authenticate(auth), name, phone, gender, availableTime, studentCard);
    }

    @GetMapping("/batches")
    public Object batches(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth) {
        approved(auth);
        var result = new java.util.LinkedHashMap<>(orders.batches());
        result.put("reminderTemplateId", reminders.templateId());
        return result;
    }

    @PostMapping("/batches/subscription")
    public Map<String, Boolean> subscribe(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth) {
        reminders.subscribe(approved(auth));
        return Map.of("ok", true);
    }

    @PostMapping("/pickup")
    public Object pickup(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
                         @RequestBody Map<String, String> body) {
        return domain.pickup(approved(auth), body.getOrDefault("token", ""));
    }

    @GetMapping("/orders")
    public Object orders(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
                         @RequestParam(defaultValue = "active") String scope,
                         @RequestParam(defaultValue = "0") int offset) {
        return orders.list(approved(auth), scope, offset);
    }

    @GetMapping("/orders/{id}")
    public Object detail(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
                         @PathVariable String id) {
        return orders.detail(approved(auth), id);
    }

    @PostMapping("/orders/{id}/complete")
    public Object complete(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
                           @PathVariable String id) {
        return domain.complete(approved(auth), id);
    }

    @PostMapping("/orders/{id}/location")
    public Object location(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
                           @PathVariable String id, @RequestBody Map<String, Object> body) {
        return domain.location(approved(auth), id, body);
    }

    @GetMapping("/finance")
    public Object finance(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth) {
        return finance.summary(approved(auth));
    }

    @GetMapping("/withdrawals")
    public Object withdrawals(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
                              @RequestParam(defaultValue = "0") int offset) {
        return finance.withdrawals(approved(auth), offset);
    }

    @GetMapping("/withdrawals/{id}")
    public Object withdrawal(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
                             @PathVariable String id) {
        return finance.withdrawal(approved(auth), id);
    }

    @PostMapping("/withdrawals")
    public Object withdraw(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
                           @RequestBody WithdrawalRequest body) {
        return domain.withdraw(approved(auth), body.amountCents(), body.requestKey());
    }

    private String approved(String auth) {
        String id = identities.authenticate(auth);
        identities.requireApproved(id);
        return id;
    }

    public record LoginRequest(String code) {
    }

    public record WithdrawalRequest(long amountCents, String requestKey) {
    }
}

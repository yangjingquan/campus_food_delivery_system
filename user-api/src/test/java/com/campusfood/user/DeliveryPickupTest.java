package com.campusfood.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.campusfood.domain.PricingRules;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.server.ResponseStatusException;

class DeliveryPickupTest {
    private static final String ORDER = "2611191001000123";
    private static final String RIDER = "R1";
    private final JdbcTemplate db = mock(JdbcTemplate.class);
    private final DeliveryService service = spy(new DeliveryService(db, mock(DeliveryBatches.class)));
    private final Map<String, Object> order = new HashMap<>();

    @BeforeEach
    void prepare() {
        order.put("id", ORDER);
        order.put("fulfill_status", "AWAITING_RIDER");
        order.put("refund_status", "NO_AFTER_SALE");
        order.put("pending_refund", false);
        order.put("locking", false);
        order.put("delivery_cents", 400L);
        when(db.queryForList(anyString(), eq(String.class), eq(RIDER))).thenReturn(List.of("测试骑手"));
        when(db.queryForList(anyString(), eq(ORDER))).thenReturn(List.of(order));
        var pricing = new OrderPricing.Applied("MERCHANT", 2, 1,
            new PricingRules(PricingRules.fixed(400), PricingRules.fixed(100), PricingRules.fixed(80)));
        doReturn(List.of(pricing)).when(db).query(contains("FROM business_order"), any(RowMapper.class), eq(ORDER));
        doReturn(Map.of("orderId", ORDER, "status", "DELIVERING", "updatedAt", 123L))
            .when(service).updateStatus(eq(ORDER), any());
    }

    private void resolves(String code) {
        when(db.queryForList(anyString(), eq(String.class), eq(code), eq(code))).thenReturn(List.of(ORDER));
    }

    @Test
    void orderNumberClaimsOrderAndRecordsCurrentRider() {
        resolves(ORDER);
        assertEquals("DELIVERING", service.pickup(" " + ORDER + "\n", RIDER, true).get("status"));
        verify(service).updateStatus(eq(ORDER), any());
        verify(db).update(anyString(), eq(RIDER), eq(123L), eq(80L), eq(ORDER));
    }

    @Test
    void legacyTicketAlsoClaimsTheSameOrder() {
        String ticket = "AbCdEf0123456789_-ABCDE";
        resolves(ticket);
        assertEquals(ORDER, service.pickup(ticket, RIDER, true).get("orderId"));
        verify(db).update(anyString(), eq(RIDER), eq(123L), eq(80L), eq(ORDER));
    }

    @Test
    void anotherRidersOrderAndOwnDuplicateAreRejected() {
        resolves(ORDER);
        order.put("pickup_rider_id", "R2");
        assertEquals("该订单已被其他骑手领取", rejected().getReason());
        order.put("pickup_rider_id", RIDER);
        assertEquals("您已领取该订单", rejected().getReason());
        verify(service, never()).updateStatus(anyString(), any());
    }

    @Test
    void closedAndNotArrivedOrdersCannotBeClaimed() {
        resolves(ORDER);
        order.put("fulfill_status", "CLOSED");
        assertEquals("该订单已关闭", rejected().getReason());
        order.put("fulfill_status", "PREPARING_FOOD");
        assertEquals("餐品尚未到达暂存点", rejected().getReason());
        verify(service, never()).updateStatus(anyString(), any());
    }

    @Test
    void pendingRefundAndLockedOrderCannotBeClaimed() {
        resolves(ORDER);
        order.put("pending_refund", true);
        assertEquals("订单售后尚待处理", rejected().getReason());
        order.put("pending_refund", false);
        order.put("locking", true);
        assertEquals("订单售后尚待处理", rejected().getReason());
        verify(service, never()).updateStatus(anyString(), any());
    }

    @Test
    void unknownAndAmbiguousCodesCannotAssignOrders() {
        when(db.queryForList(anyString(), eq(String.class), eq(ORDER), eq(ORDER))).thenReturn(List.of());
        assertEquals(HttpStatus.NOT_FOUND, rejected().getStatusCode());
        when(db.queryForList(anyString(), eq(String.class), eq(ORDER), eq(ORDER))).thenReturn(List.of(ORDER, "OTHER"));
        assertEquals(HttpStatus.CONFLICT, rejected().getStatusCode());
        verify(service, never()).updateStatus(anyString(), any());
    }

    @Test
    void riderCannotClaimStorePickupOrder() {
        resolves(ORDER);
        order.put("fulfillment_type", "pickup");
        assertEquals("到店自取订单无需骑手领取", rejected().getReason());
        verify(service, never()).updateStatus(anyString(), any());
    }

    @Test
    void unapprovedRiderCannotClaimByOrderNumber() {
        when(db.queryForList(anyString(), eq(String.class), eq(RIDER))).thenReturn(List.of());
        assertEquals(HttpStatus.FORBIDDEN, rejected().getStatusCode());
    }

    private ResponseStatusException rejected() {
        return assertThrows(ResponseStatusException.class, () -> service.pickup(ORDER, RIDER, true));
    }
}

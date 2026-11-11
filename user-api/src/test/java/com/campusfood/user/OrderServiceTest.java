package com.campusfood.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

class OrderServiceTest {
    private JdbcTemplate db;
    private AddressService addresses;
    private final Map<String, Long> minimums = new HashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        db = mock(JdbcTemplate.class);
        addresses = mock(AddressService.class);
        when(addresses.require(8L, "ADDR-1")).thenReturn(Map.of(
            "name", "林同学", "phone", "13800000000", "building", "5号宿舍楼", "room", "302寝室"));
        when(db.queryForList(contains("SELECT id,request_fingerprint"), any(Object[].class))).thenReturn(List.of());
        when(db.queryForMap(contains("SELECT fee_cents"))).thenReturn(Map.of(
            "id", 1L, "fee_cents", 400L, "food_rate", BigDecimal.TEN));
        when(db.update(anyString(), any(Object[].class))).thenReturn(1);
        doAnswer(invocation -> {
            RowMapper<?> mapper = invocation.getArgument(1);
            String productId = invocation.getArgument(2, String.class);
            boolean secondMerchant = productId.equals("P2");
            ResultSet rs = mock(ResultSet.class);
            when(rs.getString(1)).thenReturn(productId);
            when(rs.getString(2)).thenReturn(secondMerchant ? "M04" : "M03");
            when(rs.getString(3)).thenReturn(secondMerchant ? "禾风面馆" : "巷口小碗饭");
            when(rs.getString(4)).thenReturn(secondMerchant ? "招牌牛肉拌面" : "香煎鸡腿饭");
            when(rs.getString(5)).thenReturn("演示描述");
            when(rs.getLong(6)).thenReturn(secondMerchant ? 1600L : 2000L);
            when(rs.getInt(7)).thenReturn(20);
            when(rs.getInt(8)).thenReturn(0);
            when(rs.getString(9)).thenReturn("营业中");
            when(rs.getLong(10)).thenReturn(minimums.getOrDefault(productId, 0L));
            when(rs.getLong(11)).thenReturn(secondMerchant ? 36L : 10L);
            return List.of(mapper.mapRow(rs, 0));
        }).when(db).query(anyString(), any(RowMapper.class), any(Object[].class));
    }

    @Test
    void singleStoreCheckoutCreatesOneIndependentOrderAndOneFee() {
        minimums.put("P1", 2000L);
        Map<String, Object> response = checkout(List.of(Map.of("productId", "P1", "quantity", 1)), "single_key_001");

        assertEquals(2000L, response.get("foodCents"));
        assertEquals(400L, response.get("deliveryFeeCents"));
        assertEquals(2400L, response.get("totalCents"));
        assertEquals(1, response.get("merchantCount"));
        assertEquals(1, ((List<?>) response.get("merchantOrders")).size());
    }

    @Test
    void checkoutRejectsWhenOneMerchantOnlyMeetsMinimumWithDeliveryOrAnotherMerchant() {
        minimums.put("P2", 1800L);
        var error = assertThrows(ResponseStatusException.class, () -> checkout(List.of(
            Map.of("productId", "P1", "quantity", 1), Map.of("productId", "P2", "quantity", 1)), "minimum_key_001"));

        assertEquals(409, error.getStatusCode().value());
        assertTrue(error.getReason().contains("禾风面馆"));
        assertTrue(error.getReason().contains("¥18.00"));
        verify(db, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void multiStoreCheckoutCreatesOneMerchantOrderAndFeePerStore() {
        Map<String, Object> response = checkout(List.of(
            Map.of("productId", "P1", "quantity", 1), Map.of("productId", "P2", "quantity", 1)), "multi_key_001");

        assertEquals(3600L, response.get("foodCents"));
        assertEquals(800L, response.get("deliveryFeeCents"));
        assertEquals(4400L, response.get("totalCents"));
        assertEquals(2, response.get("merchantCount"));
        assertEquals(2, ((List<?>) response.get("merchantOrders")).size());
    }

    @Test
    void checkoutRetriesOrderIdCollisionWithoutReservingStockAgain() {
        when(db.update(contains("INSERT INTO business_order"), any(Object[].class)))
            .thenThrow(new DuplicateKeyException("duplicate order id"))
            .thenReturn(1);
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        LocalDateTime before = LocalDateTime.now(zone).withNano(0);

        Map<String, Object> response = checkout(List.of(Map.of("productId", "P1", "quantity", 1)), "collision_key_001");

        Map<?, ?> order = (Map<?, ?>) ((List<?>) response.get("merchantOrders")).getFirst();
        String id = (String) order.get("id");
        assertTrue(id.matches("[0-9]{12}A[0-9]{4}"));
        LocalDateTime issuedAt = LocalDateTime.parse(id.substring(0, 12), DateTimeFormatter.ofPattern("uuMMddHHmmss"));
        assertTrue(!issuedAt.isBefore(before) && !issuedAt.isAfter(LocalDateTime.now(zone)));
        verify(db, times(2)).update(contains("INSERT INTO business_order"), any(Object[].class));
        verify(db).update(contains("SET reserved=reserved+"), any(Object[].class));
        verify(db).update(contains("INSERT INTO user_order_detail"), any(Object[].class));
    }

    private Map<String, Object> checkout(List<Map<String, Object>> items, String requestKey) {
        OrderService service = new OrderService(db, addresses);
        ReflectionTestUtils.setField(service, "paymentMode", "mock");
        ReflectionTestUtils.setField(service, "unpaidTimeoutMinutes", 30);
        return service.checkout(8L, Map.of("addressId", "ADDR-1", "requestKey", requestKey, "items", items));
    }
}

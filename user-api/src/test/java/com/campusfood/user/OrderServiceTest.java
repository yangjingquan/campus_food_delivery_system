package com.campusfood.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.util.ReflectionTestUtils;

class OrderServiceTest {
    private JdbcTemplate db;
    private AddressService addresses;

    @BeforeEach
    void setUp() throws Exception {
        db = mock(JdbcTemplate.class);
        addresses = mock(AddressService.class);
        when(addresses.require(8L, "ADDR-1")).thenReturn(Map.of(
            "name", "林同学", "phone", "13800000000", "building", "5号宿舍楼", "room", "302寝室"));
        when(db.queryForList(contains("SELECT id,request_fingerprint"), any(Object[].class))).thenReturn(List.of());
        when(db.queryForObject(contains("SELECT fee_cents"), eq(Long.class))).thenReturn(400L);
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
            return List.of(mapper.mapRow(rs, 0));
        }).when(db).query(anyString(), any(RowMapper.class), any(Object[].class));
    }

    @Test
    void singleStoreCheckoutCreatesOneIndependentOrderAndOneFee() {
        Map<String, Object> response = checkout(List.of(Map.of("productId", "P1", "quantity", 1)), "single_key_001");

        assertEquals(2000L, response.get("foodCents"));
        assertEquals(400L, response.get("deliveryFeeCents"));
        assertEquals(2400L, response.get("totalCents"));
        assertEquals(1, response.get("merchantCount"));
        assertEquals(1, ((List<?>) response.get("merchantOrders")).size());
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

    private Map<String, Object> checkout(List<Map<String, Object>> items, String requestKey) {
        OrderService service = new OrderService(db, addresses);
        ReflectionTestUtils.setField(service, "paymentMode", "mock");
        ReflectionTestUtils.setField(service, "unpaidTimeoutMinutes", 30);
        return service.checkout(8L, Map.of("addressId", "ADDR-1", "requestKey", requestKey, "items", items));
    }
}

package com.campusfood.rider.service;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RiderOrdersTest {
    private final JdbcTemplate db = mock(JdbcTemplate.class);
    private final RiderOrders orders = new RiderOrders(db);

    @Test
    void activeDeliveryReadsCoordinatesFromOrderSnapshot() {
        when(db.queryForList(contains("d.pickup_rider_id=?"), eq("rider"), eq("order")))
                .thenReturn(List.of(new HashMap<>(Map.of("status", "配送中"))));
        when(db.queryForMap(contains("d.destination_latitude AS latitude"), eq("order")))
                .thenReturn(Map.of("latitude", new BigDecimal("41.8123456"),
                        "longitude", new BigDecimal("123.4123456")));
        var result = orders.detail("rider", "order");
        assertEquals(new BigDecimal("41.8123456"), result.get("latitude"));
        assertEquals(new BigDecimal("123.4123456"), result.get("longitude"));
    }

    @Test
    void endedDeliveryDoesNotExposeReceiverCoordinates() {
        when(db.queryForList(contains("d.pickup_rider_id=?"), eq("rider"), eq("order")))
                .thenReturn(List.of(new HashMap<>(Map.of("status", "已完成"))));
        assertFalse(orders.detail("rider", "order").containsKey("latitude"));
        verify(db, never()).queryForMap(anyString(), any(Object[].class));
    }
}

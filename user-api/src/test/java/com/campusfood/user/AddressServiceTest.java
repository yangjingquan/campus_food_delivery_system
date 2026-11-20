package com.campusfood.user;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AddressServiceTest {
    private final JdbcTemplate db = mock(JdbcTemplate.class);
    private final AddressService service = new AddressService(db);
    private Map<String, Object> address() {
        return Map.of("name", "同学", "phone", "13800138000", "building", "H9号楼", "room", "101");
    }

    @Test
    void missingMapSelectionCannotCreateAddressOrChangeDefaults() {
        assertThrows(ResponseStatusException.class, () -> service.create(1, address()));
        verifyNoInteractions(db);
    }

    @Test
    void editingAnotherUsersAddressIsRejectedBeforeAnyWrite() {
        when(db.queryForList(anyString(), eq("other"), eq(1L))).thenReturn(List.of());
        var error = assertThrows(ResponseStatusException.class, () -> service.update(1, "other", address()));
        assertEquals(404, error.getStatusCode().value());
        verify(db, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void createPersistsSelectedCoordinatesAndReturnsThem() {
        var body = new java.util.HashMap<>(address());
        body.put("latitude", "41.8123456");
        body.put("longitude", "123.4123456");
        when(db.queryForObject(anyString(), eq(Integer.class), eq(1L))).thenReturn(1);
        when(db.queryForList(anyString(), anyString(), eq(1L))).thenReturn(List.of(body));
        assertEquals("41.8123456", service.create(1, body).get("latitude"));
        verify(db).update(contains("INSERT INTO user_address"), anyString(), eq(1L), eq("同学"),
                eq("13800138000"), eq("H9号楼"), eq("101"), eq(new BigDecimal("41.8123456")),
                eq(new BigDecimal("123.4123456")), eq(false));
    }
}

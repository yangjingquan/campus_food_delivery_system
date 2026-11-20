package com.campusfood.user;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

class AddressCoordinatesTest {
    @Test
    void rejectsMissingPartialNonNumericAndOutOfRangeCoordinates() {
        for (Object[] pair : new Object[][] {
                {null, null}, {41.8, null}, {null, 123.4}, {"", 123.4}, {"NaN", 123.4},
                {"Infinity", 123.4}, {true, 123.4}, {"90.0000001", 123.4}, {41.8, "-180.0000001"}}) {
            var body = new HashMap<String, Object>();
            body.put("latitude", pair[0]);
            body.put("longitude", pair[1]);
            assertEquals(400, assertThrows(ResponseStatusException.class,
                    () -> AddressCoordinates.require(body)).getStatusCode().value());
        }
    }

    @Test
    void acceptsZeroAndBoundaryCoordinatesWithoutLosingPrecision() {
        var point = AddressCoordinates.require(Map.of("latitude", "41.8123456", "longitude", "123.4123456"));
        assertEquals(new BigDecimal("41.8123456"), point.latitude());
        assertEquals(new BigDecimal("123.4123456"), point.longitude());
        assertDoesNotThrow(() -> AddressCoordinates.require(Map.of("latitude", 0, "longitude", 0)));
        assertDoesNotThrow(() -> AddressCoordinates.require(Map.of("latitude", -90, "longitude", 180)));
    }
}

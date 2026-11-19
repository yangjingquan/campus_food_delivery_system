package com.campusfood.user;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class DeliveryBatchesTest {
    private static long instant(String time) {
        return LocalDateTime.parse("2026-11-19T" + time).atZone(ZoneId.of("Asia/Shanghai"))
                .toInstant().toEpochMilli();
    }

    private static Map<String, Object> rule(List<Map<String, String>> periods) {
        return new LinkedHashMap<>(Map.of("service_start", "10:30", "service_end", "20:30",
                "peak_start", "11:30", "peak_end", "13:30", "regular_batch", 30, "peak_batch", 20,
                "peakPeriods", periods));
    }

    @Test
    void secondPeakUsesPeakIntervalAndPreservesTransitionDepartures() {
        var rule = rule(List.of(Map.of("start", "11:30", "end", "13:30"),
                Map.of("start", "17:00", "end", "19:00")));
        assertEquals(instant("17:40"), DeliveryBatches.secondDeparture(instant("17:05"), rule));
        assertEquals(instant("17:20"), DeliveryBatches.secondDeparture(instant("16:55"), rule));
        assertEquals(instant("19:30"), DeliveryBatches.secondDeparture(instant("18:55"), rule));
        assertEquals(instant("18:00"), DeliveryBatches.secondDeparture(instant("17:20"), rule));
    }

    @Test
    void noPeakPeriodsUsesRegularIntervalThroughoutTheDay() {
        var rule = rule(List.of());
        assertEquals(instant("18:00"), DeliveryBatches.secondDeparture(instant("17:05"), rule));
    }

    @Test
    void legacySinglePeakRulesAndNextDayBoundaryStillWork() {
        var rule = rule(List.of());
        rule.remove("peakPeriods");
        assertEquals(instant("12:10"), DeliveryBatches.secondDeparture(instant("11:35"), rule));
        long nextDay = LocalDateTime.parse("2026-11-20T11:00").atZone(ZoneId.of("Asia/Shanghai"))
                .toInstant().toEpochMilli();
        assertEquals(nextDay, DeliveryBatches.secondDeparture(instant("20:20"), rule));
    }
}

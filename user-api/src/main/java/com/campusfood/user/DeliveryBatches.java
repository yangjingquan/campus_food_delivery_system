package com.campusfood.user;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
class DeliveryBatches {
    private static final ZoneId CAMPUS_ZONE = ZoneId.of("Asia/Shanghai");
    static final String OVERDUE = "暂存点已跨两个配送批次仍未领取";
    private static final String WAITING = "已到暂存点 / 待领取";
    private final JdbcTemplate db;

    DeliveryBatches(JdbcTemplate db) {
        this.db = db;
    }

    void recordArrival(String orderId, long now) {
        Map<String, Object> rule = db.queryForMap("""
            SELECT id,service_start,service_end,peak_start,peak_end,regular_batch,peak_batch
            FROM operations_config ORDER BY id DESC LIMIT 1
            """);
        db.update("""
            UPDATE user_order_detail SET arrived_at=?,batch_alert_at=?,batch_rule_id=?
            WHERE order_id=? AND arrived_at IS NULL
            """, now, secondDeparture(now, rule), rule.get("id"), orderId);
    }

    static long secondDeparture(long arrival, Map<String, Object> rule) {
        LocalDate day = Instant.ofEpochMilli(arrival).atZone(CAMPUS_ZONE).toLocalDate();
        LocalTime serviceStart = LocalTime.parse(rule.get("service_start").toString());
        LocalTime serviceEnd = LocalTime.parse(rule.get("service_end").toString());
        LocalTime peakStart = LocalTime.parse(rule.get("peak_start").toString());
        LocalTime peakEnd = LocalTime.parse(rule.get("peak_end").toString());
        int regular = ((Number) rule.get("regular_batch")).intValue();
        int peak = ((Number) rule.get("peak_batch")).intValue();
        if (regular < 1 || peak < 1) throw new IllegalStateException("配送批次间隔无效");
        List<Long> departures = new ArrayList<>();
        for (int offset = 0; offset < 3; offset++) {
            LocalDate date = day.plusDays(offset);
            addDepartures(departures, date, serviceStart, peakStart, regular);
            addDepartures(departures, date, peakStart, peakEnd, peak);
            addDepartures(departures, date, peakEnd, serviceEnd, regular);
        }
        return departures.stream().filter(instant -> instant > arrival).skip(1).findFirst().orElseThrow();
    }

    private static void addDepartures(List<Long> departures, LocalDate date, LocalTime from, LocalTime to, int interval) {
        if (!from.isBefore(to)) return;
        var end = date.atTime(to);
        for (var next = date.atTime(from).plusMinutes(interval); next.isBefore(end); next = next.plusMinutes(interval)) {
            departures.add(next.atZone(CAMPUS_ZONE).toInstant().toEpochMilli());
        }
        // 运营时段切换时结束当前批次，下一时段从自己的起点计算。
        departures.add(end.atZone(CAMPUS_ZONE).toInstant().toEpochMilli());
    }

    @Scheduled(fixedDelayString = "${app.delivery-batch-scan-ms:30000}", initialDelay = 10000)
    void flagOverdue() {
        long now = System.currentTimeMillis();
        var orderIds = db.queryForList("""
            SELECT o.id FROM user_order_detail d JOIN business_order o ON o.id=d.order_id
            WHERE d.batch_alert_at<=? AND o.fulfill_status=? AND o.intervene=FALSE
            ORDER BY d.batch_alert_at LIMIT 100
            """, String.class, now, WAITING);
        for (String orderId : orderIds) {
            db.update("""
                UPDATE business_order SET intervene=TRUE,intervene_reason=?
                WHERE id=? AND fulfill_status=? AND intervene=FALSE
                """, OVERDUE, orderId, WAITING);
        }
    }
}

package com.campusfood.rider.service;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RiderReminders {
    private static final ZoneId CAMPUS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(CAMPUS_ZONE);
    private final JdbcTemplate db;
    private final WechatService wechat;
    private final String templateId;
    private final String timeField;
    private final String messageField;

    public RiderReminders(JdbcTemplate db, WechatService wechat,
                          @Value("${app.reminder-template-id:}") String templateId,
                          @Value("${app.reminder-time-field:}") String timeField,
                          @Value("${app.reminder-message-field:}") String messageField) {
        this.db = db;
        this.wechat = wechat;
        this.templateId = templateId;
        this.timeField = timeField;
        this.messageField = messageField;
    }

    public String templateId() {
        return timeField.matches("(time|date)\\d+") && messageField.matches("thing\\d+") ? templateId : "";
    }

    public void subscribe(String rider) {
        String template = templateId();
        if (template.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "运营方尚未配置配送提醒模板");
        }
        // 一次性订阅最多保留一个待发送授权。最终授权额度由微信校验。
        db.update("""
            INSERT INTO rider_reminder_subscription(rider_id,template_id,remaining) VALUES(?,?,1)
            ON DUPLICATE KEY UPDATE template_id=?,remaining=1
            """, rider, template, template);
    }

    @Scheduled(fixedDelayString = "${app.reminder-scan-ms:60000}", initialDelay = 25000)
    void send() {
        String template = templateId();
        if (template.isBlank()) {
            return;
        }
        long now = System.currentTimeMillis();
        var waiting = db.queryForMap("""
            SELECT COUNT(*) AS count,COALESCE(SUM(d.batch_alert_at<=?),0) AS overdue
            FROM business_order o JOIN user_order_detail d ON d.order_id=o.id
            WHERE o.fulfill_status='已到暂存点 / 待领取' AND o.pending_refund=FALSE AND o.locking=FALSE
              AND o.refund_status NOT IN ('退款失败','平台介入') AND d.pickup_rider_id IS NULL
            """, now);
        long count = ((Number) waiting.get("count")).longValue();
        if (count == 0) {
            return;
        }
        long overdue = ((Number) waiting.get("overdue")).longValue();
        Integer minutes = db.queryForObject("SELECT reminder_interval FROM operations_config ORDER BY id DESC LIMIT 1",
            Integer.class);
        long cutoff = now - minutes * 60000L;
        var recipients = db.queryForList("""
            SELECT s.rider_id,i.openid,r.available_time FROM rider_reminder_subscription s
            JOIN rider_identity i ON i.id=s.rider_id AND i.app_id=?
            JOIN rider r ON r.id=s.rider_id AND r.audit_status='已通过'
            WHERE s.remaining=1 AND s.template_id=? AND s.last_batch_at<=?
            ORDER BY s.last_batch_at
            """, wechat.appId(), template, cutoff);
        String summary = "待领餐" + count + "单" + (overdue > 0 ? "，超时" + overdue + "单" : "");
        var fields = new LinkedHashMap<String, Object>();
        fields.put(timeField, Map.of("value", DATE.format(Instant.ofEpochMilli(now))));
        fields.put(messageField, Map.of("value", summary.substring(0, Math.min(20, summary.length()))));
        LocalTime campusTime = Instant.ofEpochMilli(now).atZone(CAMPUS_ZONE).toLocalTime();
        var available = recipients.stream().filter(recipient ->
            DeliveryAvailability.includes((String) recipient.get("available_time"), campusTime)).limit(10).toList();
        for (var recipient : available) {
            String rider = recipient.get("rider_id").toString();
            int claimed = db.update("""
                UPDATE rider_reminder_subscription SET remaining=0,last_batch_at=?,last_status='发送中'
                WHERE rider_id=? AND template_id=? AND remaining=1 AND last_batch_at<=?
                """, now, rider, template, cutoff);
            if (claimed == 1) {
                int result = wechat.sendReminder(recipient.get("openid").toString(), template, fields);
                db.update("""
                    UPDATE rider_reminder_subscription SET last_status=? WHERE rider_id=? AND last_batch_at=?
                    """, result == 0 ? "已送达微信" : "微信返回 " + result, rider, now);
            }
        }
    }
}

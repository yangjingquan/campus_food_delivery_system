package com.campusfood.user;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
class RiderTransferService {
    private static final String ORDERS = """
        SELECT w.id,w.amount_cents,w.status,r.name,i.app_id,i.openid,t.id AS transfer_id,
               c.config_version,c.channel_state,c.checked_at
        FROM withdrawal_request w
        JOIN rider_identity i ON i.id=w.account_id
        JOIN rider r ON r.id=i.id
        LEFT JOIN withdrawal_transfer t ON t.withdrawal_id=w.id
        LEFT JOIN rider_transfer_channel c ON c.withdrawal_id=w.id
        WHERE w.applicant_type='骑手'
        """;

    private final JdbcTemplate db;
    private final WechatConfigurationService config;
    private final WechatPayClient wechat;
    private final RestClient admin;
    private final String scene;
    private final List<Map<String, String>> reports;

    RiderTransferService(JdbcTemplate db, WechatConfigurationService config, WechatPayClient wechat,
                         ObjectMapper json, @Value("${app.rider-transfer.scene:}") String scene,
                         @Value("${app.rider-transfer.reports:[]}") String reports,
                         @Value("${app.rider-transfer.admin-url:http://localhost:8080}") String adminUrl,
                         @Value("${app.rider-transfer.admin-key:}") String adminKey) {
        this.db = db;
        this.config = config;
        this.wechat = wechat;
        this.scene = scene.trim();
        this.reports = json.readValue(reports, new TypeReference<>() {});
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3)).build());
        factory.setReadTimeout(Duration.ofSeconds(6));
        this.admin = RestClient.builder().baseUrl(adminUrl).requestFactory(factory)
            .defaultHeader("X-Internal-Key", adminKey).build();
    }

    @Scheduled(fixedDelayString = "${app.rider-transfer.scan-ms:30000}", initialDelay = 30000)
    void scan() {
        String scope = scene.isBlank() || reports.isEmpty()
            ? " AND w.status='打款中' AND c.config_version IS NOT NULL"
            : " AND w.status IN ('待打款','打款中')";
        if (db.queryForList("SELECT 1 FROM rider_transfer_channel LIMIT 1", Integer.class).isEmpty()
                && (scene.isBlank() || reports.isEmpty())) {
            return;
        }
        var orders = db.queryForList(ORDERS + scope + """
            AND (c.checked_at IS NULL OR c.checked_at<=?)
            ORDER BY COALESCE(c.checked_at,0),w.created_at,w.id LIMIT 20
            """, System.currentTimeMillis() - 25_000);
        for (var order : orders) {
            String id = order.get("id").toString();
            try {
                if (order.get("config_version") == null) {
                    initiate(order, id);
                } else {
                    query(order, id);
                }
            } catch (RuntimeException exception) {
                // 超时或签名未通过时只查询原单。继续冻结，避免二次付款或误解冻。
                db.update("""
                    UPDATE rider_transfer_channel SET checked_at=?,
                        channel_state=IF(channel_state='SUBMITTING','UNKNOWN',channel_state)
                    WHERE withdrawal_id=?
                    """, System.currentTimeMillis(), id);
            }
        }
    }

    private void initiate(Map<String, Object> order, String id) {
        Long version = config.currentPaymentVersion();
        if (version == null) {
            return;
        }
        String appId = order.get("app_id").toString();
        if (!appId.equals(db.queryForObject("SELECT rider_app_id FROM wechat_config WHERE id=?",
                String.class, version))) {
            return;
        }
        var credentials = config.payment(version);
        String bill = billNumber(id);
        long amount = ((Number) order.get("amount_cents")).longValue();
        var payload = new LinkedHashMap<String, Object>(Map.of(
            "appid", appId, "out_bill_no", bill, "transfer_scene_id", scene, "openid", order.get("openid"),
            "transfer_amount", amount, "transfer_remark", "校园配送收益", "transfer_scene_report_infos", reports
        ));
        if (amount >= 200_000) {
            payload.put("user_name", wechat.encryptRecipient(credentials, order.get("name").toString()));
        }
        admin.post().uri("/internal/v1/withdrawals/{id}/transfer/prepare", id).retrieve().toBodilessEntity();
        // 先持久化同一申请的固定渠道单号和凭据版本，只有成功创建渠道记录的实例发送请求。
        int claimed = db.update("""
            INSERT IGNORE INTO rider_transfer_channel(withdrawal_id,config_version,app_id,merchant_no,checked_at)
            VALUES(?,?,?,?,?)
            """, id, version, appId, credentials.merchant(), System.currentTimeMillis());
        if (claimed == 1) {
            apply(id, bill, wechat.riderTransfer(credentials, payload));
        }
    }

    private void query(Map<String, Object> order, String id) {
        var credentials = config.payment(((Number) order.get("config_version")).longValue());
        String bill = billNumber(id);
        var result = wechat.queryRiderTransfer(credentials, bill);
        // 查单的签名、商户、AppID、收款人及金额均须匹配原提现申请。
        if (bill.equals(result.path("out_bill_no").asText())
                && (!credentials.merchant().equals(result.path("mch_id").asText())
                    || !order.get("app_id").equals(result.path("appid").asText())
                    || !order.get("openid").equals(result.path("openid").asText())
                    || ((Number) order.get("amount_cents")).longValue() != result.path("transfer_amount").asLong(-1))) {
            throw WechatConfigurationService.paymentUnavailable();
        }
        apply(id, bill, result);
    }

    private void apply(String id, String bill, JsonNode result) {
        if (!bill.equals(result.path("out_bill_no").asText())) {
            db.update("UPDATE rider_transfer_channel SET checked_at=?,channel_state='UNKNOWN' WHERE withdrawal_id=?",
                System.currentTimeMillis(), id);
            return;
        }
        String state = result.path("state").asText();
        if (!List.of("ACCEPTED", "PROCESSING", "WAIT_USER_CONFIRM", "TRANSFERING",
                "SUCCESS", "FAIL", "CANCELING", "CANCELLED").contains(state)) {
            throw WechatConfigurationService.paymentUnavailable();
        }
        String packet = result.path("package_info").asText();
        if (packet.length() > 2048) {
            throw WechatConfigurationService.paymentUnavailable();
        }
        db.update("""
            UPDATE rider_transfer_channel SET channel_state=?,checked_at=?,
                package_info=CASE WHEN ? IN ('SUCCESS','FAIL','CANCELLED') THEN NULL
                    WHEN ?='' THEN package_info ELSE ? END
            WHERE withdrawal_id=?
            """, state, System.currentTimeMillis(), state, packet, packet, id);
        if (List.of("SUCCESS", "FAIL", "CANCELLED").contains(state)) {
            String reference = result.path("transfer_bill_no").asText();
            if (reference.isBlank() || reference.length() > 128) {
                throw WechatConfigurationService.paymentUnavailable();
            }
            String reason = result.path("fail_reason").asText();
            if (reason.isBlank() && !"SUCCESS".equals(state)) {
                reason = "微信转账已关闭或失败";
            }
            admin.post().uri("/internal/v1/withdrawals/{id}/transfer/result", id)
                .body(Map.of("outcome", "SUCCESS".equals(state) ? "success" : "failure",
                    "channelReference", reference, "reason", reason.substring(0, Math.min(500, reason.length()))))
                .retrieve().toBodilessEntity();
        }
    }

    private static String billNumber(String id) {
        if (!id.matches("W[0-9a-f]{32}")) {
            throw new IllegalArgumentException("提现申请编号不符合渠道单号规则");
        }
        return id.substring(1);
    }
}

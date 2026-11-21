package com.campusfood.user;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private static final Logger log = LoggerFactory.getLogger(RiderTransferService.class);
    private static final String COMMISSION_SCENE = "1005";
    private static final String COMPENSATION_DESCRIPTION = "报酬说明";
    private static final DateTimeFormatter APPLICATION_TIME =
        DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm[:ss]").withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter REPORT_TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm");
    private static final String SCENE_REQUIRED = "审核通过，未发起转账：请配置获批的微信商家转账场景及报备信息";
    private static final String MATERIAL_REQUIRED = "审核通过，未发起转账：请检查骑手 AppID、微信商户号及签名材料";
    private static final String IDENTITY_REQUIRED = "审核通过，未发起转账：骑手微信身份缺失或 AppID 与配置不一致";
    private static final String SUBMIT_BLOCKED = "审核通过，未发起转账：渠道准备失败，请检查签名材料及管理员服务";
    private static final String APPLICATION_TIME_INVALID = "审核通过，未发起转账：提现申请时间无效";
    private static final String ORDERS = """
        SELECT w.id,w.amount_cents,w.status,w.created_at,r.name,i.app_id,i.openid,t.id AS transfer_id,
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
        this.reports = readReports(json, reports);
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3)).build());
        factory.setReadTimeout(Duration.ofSeconds(6));
        this.admin = RestClient.builder().baseUrl(adminUrl).requestFactory(factory)
            .defaultHeader("X-Internal-Key", adminKey).build();
    }

    @Scheduled(fixedDelayString = "${app.rider-transfer.scan-ms:30000}", initialDelay = 30000)
    void scan() {
        boolean sceneReady = !scene.isBlank() && scene.length() <= 36 && !reports.isEmpty();
        var current = sceneReady ? config.currentRiderTransferConfiguration() : null;
        if (current == null) {
            String reason = sceneReady ? MATERIAL_REQUIRED : SCENE_REQUIRED;
            db.update("""
                UPDATE withdrawal_request SET reason=?
                WHERE applicant_type='骑手' AND status='PENDING_PAYOUT' AND NOT (reason <=> ?)
                """, reason, reason);
            if (db.queryForList("SELECT 1 FROM rider_transfer_channel LIMIT 1", Integer.class).isEmpty()) return;
        } else {
            db.update("""
                UPDATE withdrawal_request w
                LEFT JOIN rider_identity i ON i.id=w.account_id
                SET w.reason=?
                WHERE w.applicant_type='骑手' AND w.status='PENDING_PAYOUT'
                    AND (i.id IS NULL OR i.openid='' OR i.app_id<>?) AND NOT (w.reason <=> ?)
                """, IDENTITY_REQUIRED, current.appId(), IDENTITY_REQUIRED);
        }
        String scope = """
            AND ((w.status IN ('TRANSFERRING','AWAITING_CONFIRMATION') AND c.config_version IS NOT NULL)
                OR (w.status IN ('PENDING_PAYOUT','TRANSFERRING') AND c.config_version IS NULL AND i.app_id=? AND i.openid<>''))
            """;
        var orders = db.queryForList(ORDERS + scope + """
            AND (c.checked_at IS NULL OR c.checked_at<=?)
            ORDER BY CASE WHEN w.reason IN (?,?) AND c.checked_at IS NULL THEN 1 ELSE 0 END,
                COALESCE(c.checked_at,0),w.created_at,w.id LIMIT 20
            """, current == null ? null : current.appId(), System.currentTimeMillis() - 25_000, SUBMIT_BLOCKED,
            APPLICATION_TIME_INVALID);
        for (var order : orders) {
            String id = order.get("id").toString();
            try {
                if (order.get("config_version") == null) {
                    initiate(order, id, current.version());
                } else {
                    query(order, id);
                }
            } catch (DateTimeParseException exception) {
                pendingReason(id, APPLICATION_TIME_INVALID);
            } catch (RuntimeException exception) {
                // 超时或签名未通过时只查询原单。继续冻结，避免二次付款或误解冻。
                int tracked = db.update("""
                    UPDATE rider_transfer_channel SET checked_at=?,
                        channel_state=IF(channel_state='SUBMITTING','UNKNOWN',channel_state)
                    WHERE withdrawal_id=?
                    """, System.currentTimeMillis(), id);
                if (tracked > 0) {
                    processingReason(id, "渠道结果待核实，继续查询原转账单");
                } else {
                    int changed = pendingReason(id, SUBMIT_BLOCKED);
                    if (changed > 0) log.warn("Rider transfer preparation failed for {} ({})", id,
                        exception.getClass().getSimpleName());
                }
            }
        }
    }

    private void initiate(Map<String, Object> order, String id, long version) {
        String appId = order.get("app_id").toString();
        var payoutReports = reportsFor((String) order.get("created_at"));
        var credentials = config.riderTransferPayment(version);
        String bill = billNumber(id);
        long amount = ((Number) order.get("amount_cents")).longValue();
        var payload = new LinkedHashMap<String, Object>(Map.of(
            "appid", appId, "out_bill_no", bill, "transfer_scene_id", scene, "openid", order.get("openid"),
            "transfer_amount", amount, "transfer_remark", "校园配送收益", "transfer_scene_report_infos", payoutReports
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

    /** 申请时间在管理员端已按北京时间入库，生成报备时只去掉秒。 */
    List<Map<String, String>> reportsFor(String createdAt) {
        if (!COMMISSION_SCENE.equals(scene)) return reports;
        String description = LocalDateTime.parse(createdAt == null ? "" : createdAt, APPLICATION_TIME)
            .format(REPORT_TIME) + "配送费提现";
        return reports.stream()
            .map(report -> COMPENSATION_DESCRIPTION.equals(report.get("info_type"))
                ? Map.of("info_type", COMPENSATION_DESCRIPTION, "info_content", description)
                : report)
            .toList();
    }

    private int pendingReason(String id, String reason) {
        return db.update("""
            UPDATE withdrawal_request SET reason=?
            WHERE id=? AND status='PENDING_PAYOUT' AND NOT (reason <=> ?)
            """, reason, id, reason);
    }

    private void query(Map<String, Object> order, String id) {
        var credentials = config.riderTransferPayment(((Number) order.get("config_version")).longValue());
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
            processingReason(id, "渠道结果待核实，继续查询原转账单");
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
        } else {
            processingReason(id, switch (state) {
                case "WAIT_USER_CONFIRM" -> "微信已受理，请骑手在小程序内确认收款";
                case "CANCELING" -> "微信正在撤销转账，等待最终结果";
                default -> "微信正在处理转账，等待渠道结果";
            });
        }
    }

    private void processingReason(String id, String reason) {
        db.update("""
            UPDATE withdrawal_request SET reason=?
            WHERE id=? AND status IN ('TRANSFERRING','AWAITING_CONFIRMATION') AND NOT (reason <=> ?)
            """, reason, id, reason);
    }

    private static String billNumber(String id) {
        if (!id.matches("W[0-9a-f]{32}")) {
            throw new IllegalArgumentException("提现申请编号不符合渠道单号规则");
        }
        return id.substring(1);
    }

    private static List<Map<String, String>> readReports(ObjectMapper json, String value) {
        try {
            var reports = json.readValue(value, new TypeReference<List<Map<String, String>>>() {});
            if (reports != null && reports.stream().allMatch(report -> report != null
                    && report.get("info_type") != null && !report.get("info_type").isBlank()
                    && report.get("info_content") != null && !report.get("info_content").isBlank())) {
                return reports;
            }
        } catch (tools.jackson.core.JacksonException ignored) {
            // 报备配置错误只阻塞新转账，已有单据仍须查单。
        }
        log.warn("RIDER_TRANSFER_SCENE_REPORTS must contain a JSON array of transfer scene reports");
        return List.of();
    }
}

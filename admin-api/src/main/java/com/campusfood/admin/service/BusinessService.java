package com.campusfood.admin.service;

import java.math.BigDecimal;
import java.time.*;
import com.campusfood.domain.PricingRules;
import com.campusfood.domain.PricingRules.Mode;
import com.campusfood.domain.PricingRules.Rule;
import java.time.format.DateTimeFormatter;
import java.util.*;

import org.springframework.http.HttpStatus;
import com.campusfood.admin.dao.BusinessDao;
import com.campusfood.admin.dao.ConfigurationLockMapper;
import com.campusfood.admin.dao.OperationsConfigMapper;
import com.campusfood.admin.dao.MerchantMapper;
import com.campusfood.admin.dao.RiderMapper;
import com.campusfood.admin.dao.RefundRecordMapper;
import com.campusfood.admin.dao.WithdrawalRequestMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** 提供运营概览、业务查询及运营配置操作。 */
@Service
public class BusinessService {
    private final BusinessDao db;
    private final ConfigurationLockMapper configurationLocks;
    private final OperationsConfigMapper operations;
    private final MerchantMapper merchants;
    private final RiderMapper riders;
    private final RefundRecordMapper refunds;
    private final WithdrawalRequestMapper withdrawals;

    public BusinessService(BusinessDao db, MerchantMapper merchants, RiderMapper riders,
                           RefundRecordMapper refunds, WithdrawalRequestMapper withdrawals,
                           ConfigurationLockMapper configurationLocks, OperationsConfigMapper operations) {
        this.db = db;
        this.configurationLocks = configurationLocks;
        this.operations = operations;
        this.merchants = merchants;
        this.riders = riders;
        this.refunds = refunds;
        this.withdrawals = withdrawals;
    }

    private List<Map<String, Object>> orders() {
        List<Map<String, Object>> rows = db.listOrders();
        for (Map<String, Object> row : rows) row.put("coordinationNotes", db.listOrderNotes(row.get("id")));
        return rows;
    }

    private int count(Integer n) {
        return n == null ? 0 : n;
    }

    public Map<String, Object> overview() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderCount", count(db.countOrders()));
        Long paid = db.sumPaid();
        data.put("paidCents", paid == null ? 0 : paid);
        data.put("deliveringCount", count(db.countDeliveringOrders()));
        data.put("interveneCount", count(db.countInterventionOrders()));
        data.put("todoMerchants", count(merchants.countPending()));
        data.put("todoRiders", count(riders.countPending()));
        data.put("failedRefunds", count(refunds.countFailed()));
        data.put("todoWithdrawals", count(withdrawals.countPending()));
        // 按北京时间划分自然日，再转换为时间戳统计近七天订单。
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        LocalDate today = LocalDate.now(zone);
        List<Map<String, Object>> trend = new ArrayList<>();
        for (int i = 6; i >= 0; i--) {
            LocalDate date = today.minusDays(i);
            long start = date.atStartOfDay(zone).toInstant().toEpochMilli();
            long end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
            Integer n = db.countOrdersInPeriod(start, end);
            trend.add(Map.of("label", date.format(DateTimeFormatter.ofPattern("MM.dd")), "value", n == null ? 0 : n));
        }
        data.put("trend", trend);
        return data;
    }

    public Map<String, Object> workspace(String section) {
        return switch (section) {
            case "orders" -> Map.of("rows", orders());
            case "aftersales" -> Map.of("rows", db.listRefunds(), "orders", orders());
            case "withdrawals" -> Map.of("rows", db.listWithdrawals());
            case "finance" -> Map.of("accounts", db.listAccounts(),
                    "ledger", db.listLedger(),
                    "journal", db.listJournal(),
                    "debts", db.listDebts());
            case "config" -> {
                List<Map<String, Object>> all = db.listOperationsConfigs();
                for (var config : all) {
                    config.put("peakPeriods", db.listPeakPeriods(config.remove("configId")));
                    config.put("pricing", defaultPricing(config));
                }
                yield Map.of("config", all.get(0), "history", all.subList(1, all.size()));
            }
            default -> throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, "该模块尚未接入");
        };
    }

    private static String field(Map<?, ?> p, String key) {
        Object v = p.get(key);
        return v == null ? "" : v.toString().trim();
    }

    private static int number(Map<?, ?> p, String key, int min, int max) {
        try {
            int n = Integer.parseInt(field(p, key));
            if (n >= min && n <= max) return n;
        } catch (NumberFormatException ignored) {
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "无效参数：" + key);
    }

    private static java.math.BigDecimal rate(Map<?, ?> p, String key) {
        try {
            var n = new java.math.BigDecimal(field(p, key));
            if (n.scale() <= 2 && n.compareTo(java.math.BigDecimal.ZERO) >= 0 &&
                    n.compareTo(new java.math.BigDecimal("100")) <= 0) return n;
        } catch (NumberFormatException ignored) {
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "无效比例：" + key);
    }

    private static String time(Map<?, ?> p, String key) {
        String t = field(p, key);
        if (!t.matches("([01][0-9]|2[0-3]):[0-5][0-9]"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "无效时间：" + key);
        return t;
    }

    @Transactional
    public Map<String, Object> action(String type, Map<?, ?> p, String actor) {
        if (type.equals("order.coordinate")) {
            String id = field(p, "id"), note = field(p, "note");
            if (note.isEmpty() || note.length() > 500)
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请填写不超过 500 字的协调记录");
            if (countById("business_order", id) == 0)
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "订单不存在");
            db.insertOrderNote(id, actor, note);
            audit(actor, type, id, "", null, note);
            return Map.of("ok", true);
        }
        if (type.equals("withdrawal.review")) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "提现审核依赖交易服务校验及冻结资金，尚未接入，未变更申请状态");
        }
        if (type.equals("config.save")) {
            if (!(p.get("patch") instanceof Map<?, ?> c))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少配置");
            // 配置保存采用锁和版本号共同防止并发覆盖。
            configurationLocks.lock("operations");
            long id = operations.lockLatestVersion();
            String current = "OPS-V" + String.format("%03d", id);
            if (!current.equals(field(c, "version")))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "配置已被修改，请刷新后重试");
            int regular = number(c, "regularBatch", 1, 1440), peak =
                    number(c, "peakBatch", 1, 1440), accept = number(c, "acceptTimeout", 1, 1440), reminder =
                    number(c, "reminderInterval", 1, 1440);
            var previous = operations.selectById(id);
            int merchantWait = c.containsKey("merchantSettlementWaitMinutes")
                ? number(c, "merchantSettlementWaitMinutes", 0, Integer.MAX_VALUE) : previous.getMerchantSettlementWaitMinutes();
            int riderWait = c.containsKey("riderSettlementWaitMinutes")
                ? number(c, "riderSettlementWaitMinutes", 0, Integer.MAX_VALUE) : previous.getRiderSettlementWaitMinutes();
            var rules = c.containsKey("pricing") ? PricingConfigurationService.parse(c.get("pricing"))
                : new PricingRules(PricingRules.fixed(number(c, "feeCents", 0, 100000)),
                    PricingRules.percentage(rate(c, "foodRate")), PricingRules.percentage(rate(c, "deliveryRate")));
            String start = time(c, "serviceStart"), end = time(c, "serviceEnd");
            if (start.compareTo(end) >= 0)
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "配送服务开始时间须早于结束时间");
            var periods = peakPeriods(c, start, end);
            // 保留旧字段以兼容已有客户端，无高峰时段时使用零长度窗口。
            String ps = periods.isEmpty() ? start : periods.getFirst().get("start");
            String pe = periods.isEmpty() ? start : periods.getFirst().get("end");
            db.insertOperationsConfig(fixedValue(rules.fee()) == null ? 0 : rules.fee().value(),
                rateValue(rules.food()), rateValue(rules.delivery()),
                rules.fee().mode() == Mode.PERCENTAGE ? rules.fee().value() : null,
                fixedValue(rules.food()), fixedValue(rules.delivery()),
                regular, peak, start, end, ps, pe, accept, reminder, merchantWait, riderWait, actor);
            long configId = operations.lockLatestVersion();
            for (var period : periods) db.insertPeakPeriod(configId, period.get("start"), period.get("end"));
            audit(actor, type, current, current, "NEW_VERSION", "");
            return Map.of("ok", true);
        }
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "此操作依赖交易或支付渠道，尚未接入，未执行任何资金操作");
    }

    private static PricingRules defaultPricing(Map<String, Object> config) {
        Object feeBps = config.remove("feeBps");
        Object foodFixed = config.remove("foodFixed");
        Object deliveryFixed = config.remove("deliveryFixed");
        return new PricingRules(
            feeBps instanceof Number n ? new Rule(Mode.PERCENTAGE, n.intValue())
                : PricingRules.fixed(((Number) config.get("feeCents")).intValue()),
            foodFixed instanceof Number n ? PricingRules.fixed(n.intValue())
                : PricingRules.percentage((BigDecimal) config.get("foodRate")),
            deliveryFixed instanceof Number n ? PricingRules.fixed(n.intValue())
                : PricingRules.percentage((BigDecimal) config.get("deliveryRate")));
    }

    private static Integer fixedValue(Rule rule) {
        return rule.mode() == Mode.FIXED ? rule.value() : null;
    }

    private static BigDecimal rateValue(Rule rule) {
        return rule.mode() == Mode.PERCENTAGE ? BigDecimal.valueOf(rule.value(), 2) : BigDecimal.ZERO;
    }

    private static List<Map<String, String>> peakPeriods(Map<?, ?> config, String serviceStart, String serviceEnd) {
        Object value = config.get("peakPeriods");
        if (!config.containsKey("peakPeriods")) {
            if (field(config, "peakStart").equals(field(config, "peakEnd"))
                    && field(config, "peakStart").matches("([01][0-9]|2[0-3]):[0-5][0-9]")) return List.of();
            value = List.of(Map.of("start", field(config, "peakStart"), "end", field(config, "peakEnd")));
        }
        if (!(value instanceof List<?> values) || values.size() > 48)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "高峰时段格式无效，最多支持 48 个");
        List<Map<String, String>> periods = new ArrayList<>();
        for (Object item : values) {
            if (!(item instanceof Map<?, ?> period))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "高峰时段格式无效");
            String start = time(period, "start"), end = time(period, "end");
            if (start.compareTo(end) >= 0 || start.compareTo(serviceStart) < 0 || end.compareTo(serviceEnd) > 0)
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "高峰时段须在配送服务时间内，且开始时间早于结束时间");
            periods.add(Map.of("start", start, "end", end));
        }
        periods.sort(Comparator.comparing(period -> period.get("start")));
        for (int i = 1; i < periods.size(); i++) {
            if (periods.get(i).get("start").compareTo(periods.get(i - 1).get("end")) < 0)
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "高峰时段不能重叠");
        }
        return periods;
    }

    private int countById(String table, String id) {
        Integer n = db.countRecordById(table, id);
        return n == null ? 0 : n;
    }

    private void audit(String actor, String action, String id, String before, String after, String reason) {
        db.insertAudit(actor, action, id, before, after, reason);
    }
}

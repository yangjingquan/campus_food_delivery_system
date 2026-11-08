package com.campusfood.admin.service;

import java.time.*;
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
            audit(actor, type, id, "", "", note);
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
            int fee = number(c, "feeCents", 0, 100000), regular = number(c, "regularBatch", 1, 1440), peak =
                    number(c, "peakBatch", 1, 1440), accept = number(c, "acceptTimeout", 1, 1440), reminder =
                    number(c, "reminderInterval", 1, 1440);
            var food = rate(c, "foodRate");
            var delivery = rate(c, "deliveryRate");
            String start = time(c, "serviceStart"), end = time(c, "serviceEnd"), ps = time(c, "peakStart"), pe =
                    time(c, "peakEnd");
            if (start.compareTo(end) >= 0 || ps.compareTo(pe) >= 0 || ps.compareTo(start) < 0 || pe.compareTo(end) > 0)
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "服务与高峰时段无效");
            db.insertOperationsConfig(fee, food, delivery, regular, peak, start, end, ps, pe, accept, reminder, actor);
            audit(actor, type, current, current, "新版本", "");
            return Map.of("ok", true);
        }
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "此操作依赖交易或支付渠道，尚未接入，未执行任何资金操作");
    }

    private int countById(String table, String id) {
        Integer n = db.countRecordById(table, id);
        return n == null ? 0 : n;
    }

    private void audit(String actor, String action, String id, String before, String after, String reason) {
        db.insertAudit(actor, action, id, before, after, reason);
    }
}

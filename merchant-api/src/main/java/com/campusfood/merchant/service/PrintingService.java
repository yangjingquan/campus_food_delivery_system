package com.campusfood.merchant.service;

import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** 持久化任务将接单结果与打印平台故障隔离，结果不明的提交交由商户确认补打。 */
@Service
public class PrintingService {
    private final JdbcTemplate db;
    private final OrderService orders;
    private final PrinterCredentials credentials;
    private final SpyunClient cloud;
    private final ReceiptFormatter formatter;
    private final TransactionTemplate transactions;

    public PrintingService(JdbcTemplate db, OrderService orders, PrinterCredentials credentials,
                           SpyunClient cloud, ReceiptFormatter formatter, TransactionTemplate transactions) {
        this.db = db;
        this.orders = orders;
        this.credentials = credentials;
        this.cloud = cloud;
        this.formatter = formatter;
        this.transactions = transactions;
    }

    public Map<String, Object> device(String merchant) {
        return credentials.status(merchant);
    }

    public Map<String, Object> bind(String merchant) {
        try {
            cloud.bind(credentials.load(merchant));
            return Map.of("configured", true, "message", "本店设备已连接商鹏云，接单后自动提交小票");
        } catch (SpyunClient.PrintException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, e.getMessage());
        }
    }

    /** 在请求接单前提交意图，接单响应丢失时后台仍可根据已提交的订单状态执行打印。 */
    public void prepareAcceptance(String merchant, String orderId) {
        var order = orders.detail(merchant, orderId);
        if (!java.util.List.of("待商户接单", "商户出餐中").contains(order.get("status"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "当前订单状态不可接单");
        }
        if ("待商户接单".equals(order.get("status"))) {
            insert(merchant, orderId, "auto:" + orderId);
        }
    }

    public Map<String, Object> reprint(String merchant, String orderId, Map<String, Object> body) {
        String key = Inputs.text(body, "requestKey", "补打申请编号", 64, true);
        if (!key.matches("[A-Za-z0-9_-]{8,64}")) throw Inputs.invalid("补打申请编号无效");
        return transactions.execute(transaction -> {
            // 以订单行串行化同一订单的不同补打申请，避免双击和多端操作重复排队。
            var owned = db.queryForList("SELECT id FROM business_order WHERE id=? AND merchant_id=? FOR UPDATE",
                    orderId, merchant);
            if (owned.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "订单不存在");

            var existing = db.queryForList("""
                SELECT id,order_id AS orderId,status,message,provider_id AS providerId
                FROM merchant_print_job WHERE merchant_id=? AND request_key=?
                """, merchant, "manual:" + key);
            if (!existing.isEmpty()) {
                if (!orderId.equals(existing.getFirst().get("orderId"))) throw Inputs.invalid("补打申请编号已用于其他订单");
                return existing.getFirst();
            }
            orders.ticket(merchant, orderId);
            var active = db.queryForList("""
                SELECT id FROM merchant_print_job WHERE merchant_id=? AND order_id=? AND status IN ('waiting','sending')
                """, merchant, orderId);
            if (!active.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "该订单正在排队或提交打印，请刷新小票状态");
            formatter.format(orders.receipt(merchant, orderId));
            credentials.load(merchant);
            insert(merchant, orderId, "manual:" + key);
            var result = db.queryForMap("""
                SELECT id,order_id AS orderId,status,message,provider_id AS providerId
                FROM merchant_print_job WHERE merchant_id=? AND request_key=?
                """, merchant, "manual:" + key);
            if (!orderId.equals(result.get("orderId"))) throw Inputs.invalid("补打申请编号已用于其他订单");
            return result;
        });
    }

    public Map<String, Object> latest(String merchant, String orderId) {
        var jobs = db.queryForList("""
            SELECT id,status,message,provider_id AS providerId,created_at AS createdAt
            FROM merchant_print_job WHERE merchant_id=? AND order_id=? ORDER BY created_at DESC,id DESC LIMIT 1
            """, merchant, orderId);
        return jobs.isEmpty() ? Map.of("status", "none", "message", "尚无打印任务") : jobs.getFirst();
    }

    private void insert(String merchant, String orderId, String key) {
        long now = System.currentTimeMillis();
        db.update("""
            INSERT INTO merchant_print_job(id,merchant_id,order_id,request_key,created_at,updated_at,message)
            VALUES(?,?,?,?,?,?,'已排队，等待提交打印') ON DUPLICATE KEY UPDATE request_key=request_key
            """, UUID.randomUUID().toString(), merchant, orderId, key, now, now);
    }

    @Scheduled(fixedDelay = 2000)
    public void process() {
        long cutoff = System.currentTimeMillis() - 120_000;
        // 商鹏接口没有幂等键。进程异常后的提交不能自动重发，以免多出一份小票。
        db.update("""
            UPDATE merchant_print_job SET status='unknown',message='提交结果待确认，请先核对设备出纸，再决定补打',updated_at=?
            WHERE status='sending' AND updated_at<?
            """, System.currentTimeMillis(), cutoff);
        db.update("""
            UPDATE merchant_print_job j JOIN business_order o ON o.id=j.order_id
            SET j.status='failed',j.message='订单未接单或已关闭，未提交打印',j.updated_at=?
            WHERE j.status='waiting' AND (o.fulfill_status='已关闭' OR (o.accepted=FALSE AND j.created_at<?))
            """, System.currentTimeMillis(), cutoff);
        var jobs = db.queryForList("""
            SELECT j.id,j.merchant_id AS merchantId,j.order_id AS orderId
            FROM merchant_print_job j JOIN business_order o ON o.id=j.order_id
            WHERE j.status='waiting' AND o.accepted=TRUE AND o.fulfill_status<>'已关闭'
            ORDER BY j.created_at LIMIT 10
            """);
        for (var job : jobs) execute(job);
    }

    private void execute(Map<String, Object> job) {
        String id = job.get("id").toString();
        int claimed = db.update("""
            UPDATE merchant_print_job SET status='sending',message='正在提交商鹏云打印',updated_at=?
            WHERE id=? AND status='waiting'
            """, System.currentTimeMillis(), id);
        if (claimed == 0) return;
        String merchant = job.get("merchantId").toString();
        String status = "failed";
        String message;
        String providerId = "";
        try {
            var device = credentials.load(merchant);
            String content = formatter.format(orders.receipt(merchant, job.get("orderId").toString()));
            cloud.bind(device);
            providerId = cloud.print(device, content);
            status = "submitted";
            message = "已提交商鹏云，请查看打印机出纸情况";
        } catch (SpyunClient.PrintException e) {
            status = e.uncertain() ? "unknown" : "failed";
            message = e.getMessage();
        } catch (ResponseStatusException e) {
            message = e.getReason();
        } catch (RuntimeException e) {
            // 不将数据库或上游异常细节返回小程序。
            message = "生成小票失败，请刷新订单并核对设备配置后补打";
        }
        db.update("""
            UPDATE merchant_print_job SET status=?,message=?,provider_id=?,updated_at=? WHERE id=? AND status='sending'
            """, status, message, providerId, System.currentTimeMillis(), id);
    }
}

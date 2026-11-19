package com.campusfood.merchant.service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

/** 58mm 小票按 32 个半角字宽排版，长商品名换行且完整保留。 */
@Component
public class ReceiptFormatter {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.of("Asia/Shanghai"));

    public String format(Map<String, Object> ticket) {
        StringBuilder receipt = new StringBuilder("<C><B><L1>")
                .append(text(ticket.get("merchantName"))).append("</L1></B></C><BR>")
                .append("<QRCODE>").append(text(ticket.get("id"))).append("</QRCODE><BR>");
        receipt.append(column("名称", 12)).append(column("单价", 7))
                .append(column("数量", 5)).append("金额<BR>");
        for (Map<String, Object> item : lines(ticket)) {
            List<String> names = wrap(text(item.get("name")), 12);
            for (int i = 0; i < names.size() - 1; i++) receipt.append(names.get(i)).append("<BR>");
            receipt.append(column(names.getLast(), 12)).append(column(money(item.get("priceCents")), 7))
                    .append(column(item.get("quantity").toString(), 5))
                    .append(money(item.get("amountCents"))).append("<BR>");
        }
        receipt.append("--------------------------------<BR>")
                .append("配送费：¥").append(money(ticket.get("deliveryCents"))).append("<BR>")
                .append("<B>总金额：¥").append(money(ticket.get("paidCents"))).append("</B><BR>")
                .append("送货地址：").append(text(ticket.get("address"))).append("<BR>")
                .append("下单时间：").append(time(ticket.get("createdAt"))).append("<BR><BR>");
        String content = receipt.toString();
        if (content.getBytes(StandardCharsets.UTF_8).length > 5000) {
            throw Inputs.invalid("完整小票内容超过打印机 5000 字节限制，请复制小票内容处理");
        }
        return content;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> lines(Map<String, Object> ticket) {
        return (List<Map<String, Object>>) ticket.get("lines");
    }

    static String money(Object cents) {
        return BigDecimal.valueOf(((Number) cents).longValue(), 2).toPlainString();
    }

    static String time(Object value) {
        if (value instanceof Timestamp timestamp) return TIME.format(timestamp.toInstant());
        throw new IllegalArgumentException("下单时间不可用");
    }

    static String text(Object value) {
        // 全角尖括号阻止商户名称、商品名和地址注入打印机控制标签。
        return String.valueOf(value).replace('<', '＜').replace('>', '＞').replace('&', '＆')
                .replaceAll("[\\p{Cntrl}]", " ");
    }

    private String column(String value, int width) {
        int actual = value.codePoints().map(code -> code < 128 ? 1 : 2).sum();
        return value + " ".repeat(Math.max(0, width - actual));
    }

    private List<String> wrap(String value, int width) {
        var rows = new java.util.ArrayList<String>();
        StringBuilder line = new StringBuilder();
        int used = 0;
        for (int code : value.codePoints().toArray()) {
            int size = code < 128 ? 1 : 2;
            if (used + size > width) {
                rows.add(line.toString());
                line.setLength(0);
                used = 0;
            }
            line.appendCodePoint(code);
            used += size;
        }
        rows.add(line.toString());
        return rows;
    }
}

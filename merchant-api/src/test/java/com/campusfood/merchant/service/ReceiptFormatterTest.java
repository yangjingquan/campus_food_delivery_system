package com.campusfood.merchant.service;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class ReceiptFormatterTest {
    private final ReceiptFormatter formatter = new ReceiptFormatter();

    private Map<String, Object> ticket(String name) {
        return Map.of("id", "202611190001", "merchantName", "校园餐厅<B>测试</B>",
                "lines", List.of(Map.of("name", name, "priceCents", 1250L, "quantity", 2, "amountCents", 2500L)),
                "deliveryCents", 300L, "paidCents", 2800L, "address", "一号楼 101<CUT>",
                "createdAt", Timestamp.from(Instant.parse("2026-11-19T02:03:04Z")));
    }

    @Test
    void printsAllRequiredFieldsInOrderAndEscapesPrinterTags() {
        String value = formatter.format(ticket("宫保鸡丁套餐"));
        assertTrue(value.startsWith("<C><B><L1>校园餐厅＜B＞测试＜/B＞</L1></B></C>"));
        List<String> expected = List.of("<QRCODE>202611190001</QRCODE>", "名称", "宫保鸡丁套餐",
                "12.50", "2", "25.00", "--------------------------------", "配送费：¥3.00",
                "总金额：¥28.00", "送货地址：一号楼 101＜CUT＞", "下单时间：2026-11-19 10:03:04");
        int previous = -1;
        for (String field : expected) {
            int index = value.indexOf(field, previous + 1);
            assertTrue(index > previous, field);
            previous = index;
        }
        assertEquals(1, value.split("<QRCODE>", -1).length - 1);
        assertFalse(value.contains("<CUT>"));
    }

    @Test
    void wrapsLongChineseNamesWithoutDroppingCharacters() {
        String value = formatter.format(ticket("超长的商品名称需要换行且保留全部文字"));
        assertTrue(value.contains("超长的商品名<BR>称需要换行且<BR>保留全部文字"));
    }

    @Test
    void refusesOversizedReceiptInsteadOfSilentlyDroppingItems() {
        assertThrows(ResponseStatusException.class, () -> formatter.format(ticket("餐".repeat(2000))));
    }
}

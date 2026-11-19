package com.campusfood.merchant.service;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

class PrintingServiceTest {
    private final JdbcTemplate db = mock(JdbcTemplate.class);
    private final OrderService orders = mock(OrderService.class);
    private final PrinterCredentials credentials = mock(PrinterCredentials.class);
    private final SpyunClient cloud = mock(SpyunClient.class);
    private final ReceiptFormatter formatter = mock(ReceiptFormatter.class);
    private final TransactionTemplate transactions = mock(TransactionTemplate.class);
    private final PrintingService service = new PrintingService(db, orders, credentials, cloud, formatter,
            transactions);
    private final PrinterCredentials.Credentials device =
            new PrinterCredentials.Credentials("app", "secret", "store-a-sn", "pkey", "店铺 A");

    private void job(int claim) {
        when(db.queryForList(contains("SELECT j.id"))).thenReturn(
                List.of(Map.of("id", "job-1", "merchantId", "store-a", "orderId", "order-a")));
        when(db.update(contains("status='sending',message="), anyLong(), eq("job-1"))).thenReturn(claim);
        when(credentials.load("store-a")).thenReturn(device);
        when(orders.receipt("store-a", "order-a")).thenReturn(Map.of("id", "order-a"));
        when(formatter.format(anyMap())).thenReturn("receipt");
    }

    @Test
    void workerUsesOwningMerchantCredentialsAndPersistsProviderId() {
        job(1);
        when(cloud.print(device, "receipt")).thenReturn("provider-1");
        service.process();
        verify(cloud).bind(device);
        verify(cloud).print(device, "receipt");
        verify(db).update(contains("SET status=?,message=?"), eq("submitted"), anyString(),
                eq("provider-1"), anyLong(), eq("job-1"));
        verify(credentials, never()).load("store-b");
    }

    @Test
    void concurrentWorkerCannotSubmitAlreadyClaimedJob() {
        job(0);
        service.process();
        verifyNoInteractions(cloud);
    }

    @Test
    void uncertainPrintIsPersistedForExplicitReprint() {
        job(1);
        when(cloud.print(device, "receipt")).thenThrow(new SpyunClient.PrintException("结果待确认", true));
        service.process();
        verify(db).update(contains("SET status=?,message=?"), eq("unknown"), eq("结果待确认"), eq(""),
                anyLong(), eq("job-1"));
        verify(cloud, times(1)).print(device, "receipt");
    }

    @Test
    void bindingFailureDoesNotSendPrint() {
        job(1);
        doThrow(new SpyunClient.PrintException("设备配置错误", false)).when(cloud).bind(device);
        service.process();
        verify(cloud, never()).print(any(), anyString());
        verify(db).update(contains("SET status=?,message=?"), eq("failed"), eq("设备配置错误"), eq(""),
                anyLong(), eq("job-1"));
    }

    @Test
    void acceptanceIntentIsRecordedOncePerOrderAndOnlyAfterOwnershipCheck() {
        when(orders.detail("store-a", "order-a")).thenReturn(Map.of("status", "待商户接单"));
        service.prepareAcceptance("store-a", "order-a");
        verify(db).update(contains("ON DUPLICATE KEY UPDATE"), anyString(), eq("store-a"), eq("order-a"),
                eq("auto:order-a"), anyLong(), anyLong());
        verifyNoInteractions(cloud);
    }

    @Test
    void closedOrderCannotQueueAnAcceptancePrint() {
        when(orders.detail("store-a", "order-a")).thenReturn(Map.of("status", "已关闭"));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> service.prepareAcceptance("store-a", "order-a"));
        verifyNoInteractions(db, cloud);
    }

    @Test
    void repeatedManualRequestReturnsOriginalJobWithoutPrintingAgain() {
        when(transactions.execute(any())).thenAnswer(invocation -> {
            org.springframework.transaction.support.TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(new org.springframework.transaction.support.SimpleTransactionStatus());
        });
        when(db.queryForList(contains("SELECT id FROM business_order"), eq("order-a"), eq("store-a")))
                .thenReturn(List.of(Map.of("id", "order-a")));
        var original = Map.<String, Object>of("id", "job-1", "orderId", "order-a", "status", "submitted",
                "message", "已提交", "providerId", "provider-1");
        when(db.queryForList(contains("request_key=?"), eq("store-a"), eq("manual:request-123")))
                .thenReturn(List.of(original));

        assertEquals(original, service.reprint("store-a", "order-a", Map.of("requestKey", "request-123")));
        verifyNoInteractions(orders, cloud);
        verify(db, never()).update(contains("INSERT INTO"), any(Object[].class));
    }

    @Test
    void anotherMerchantCannotReprintTheOrder() {
        when(transactions.execute(any())).thenAnswer(invocation -> {
            org.springframework.transaction.support.TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(new org.springframework.transaction.support.SimpleTransactionStatus());
        });
        when(db.queryForList(contains("SELECT id FROM business_order"), eq("order-a"), eq("store-b")))
                .thenReturn(List.of());
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> service.reprint("store-b", "order-a", Map.of("requestKey", "request-123")));
        verifyNoInteractions(orders, cloud);
    }

    @Test
    void deviceCredentialsAreRedactedInToString() {
        assertFalse(device.toString().contains("secret"));
        assertFalse(device.toString().contains("pkey"));
    }
}

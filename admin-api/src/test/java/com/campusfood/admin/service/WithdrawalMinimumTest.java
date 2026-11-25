package com.campusfood.admin.service;

import java.util.List;
import java.util.Map;

import com.campusfood.admin.dao.FinanceAccountMapper;
import com.campusfood.admin.dao.FinanceDebtMapper;
import com.campusfood.admin.dao.WithdrawalDao;
import com.campusfood.admin.dao.WithdrawalIntentMapper;
import com.campusfood.admin.dao.WithdrawalRequestMapper;
import com.campusfood.admin.dao.WithdrawalTransferMapper;
import com.campusfood.domain.BusinessStates.Withdrawal;
import com.campusfood.domain.BusinessStates.WithdrawalIntent;
import com.campusfood.domain.BusinessStates.WithdrawalTransfer;
import com.campusfood.domain.WithdrawalLimits;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WithdrawalMinimumTest {
    private static final String ID = "W" + "a".repeat(32);
    private static final String ACCOUNT = "RIDER_QA";
    private static final String KEY = "rider-test-key";
    private static final Map<String, String> CHANNEL = Map.of("appId", "wx-qa", "merchantNo", "qa-merchant");
    private final WithdrawalDao db = mock(WithdrawalDao.class);
    private final FinanceAccountMapper accounts = mock(FinanceAccountMapper.class);
    private final FinanceDebtMapper debts = mock(FinanceDebtMapper.class);
    private final WithdrawalIntentMapper intents = mock(WithdrawalIntentMapper.class);
    private final WithdrawalRequestMapper requests = mock(WithdrawalRequestMapper.class);
    private final WithdrawalTransferMapper transfers = mock(WithdrawalTransferMapper.class);
    private final WithdrawalService service = new WithdrawalService(db, accounts, debts, intents, requests, transfers);

    @Test
    void oneCentIsRejectedBeforeAnyFundsAreFrozen() {
        mockNewRequest(1, "RIDER");
        var error = assertThrows(ResponseStatusException.class, () -> service.request(request(1)));
        assertEquals(400, error.getStatusCode().value());
        verify(accounts, never()).freezeAvailable(anyLong(), anyLong(), anyString());
    }

    @Test
    void tenCentsAndThreeYuanTwentyCanStillBeRequested() {
        for (long amount : new long[] {10, 320}) {
            mockNewRequest(amount, "RIDER");
            assertEquals("ACCEPTED", service.request(request(amount)).get("status"));
            verify(accounts).freezeAvailable(amount, amount, ACCOUNT);
        }
    }

    @Test
    void merchantNineCentsIsRejectedBeforeDebtRecoveryOrFreeze() {
        mockNewRequest(9, "MERCHANT");
        var error = assertThrows(ResponseStatusException.class, () -> service.request(request(9)));
        assertEquals(400, error.getStatusCode().value());
        assertEquals(WithdrawalLimits.MERCHANT_MINIMUM_MESSAGE, error.getReason());
        verifyNoInteractions(debts);
        verify(accounts, never()).freezeAvailable(anyLong(), anyLong(), anyString());
    }

    @Test
    void merchantTenCentsPassesTheMinimum() {
        mockNewRequest(10, "MERCHANT");
        assertEquals("ACCEPTED", service.request(request(10)).get("status"));
        verify(accounts).freezeAvailable(10, 10, ACCOUNT);
    }

    @Test
    void historicalMerchantRequestBelowMinimumCanStillBeReadByItsOriginalKey() {
        when(intents.lockIntent(KEY)).thenReturn(Map.of(
            "account_id", ACCOUNT, "requested_cents", 9L, "status", "ACCEPTED", "withdrawal_id", ID));

        assertEquals(ID, service.request(request(9)).get("withdrawalId"));
        verifyNoInteractions(accounts, debts);
    }

    @Test
    void rejectedOneCentIsReturnedOnlyOnceWithNoWechatReference() {
        when(requests.lockWithdrawal(ID)).thenReturn(
            List.of(row("TRANSFERRING", WithdrawalLimits.REJECTED_SUBMISSION_REASON)),
            List.of(row("PAYOUT_FAILED", WithdrawalLimits.RETURNED_BELOW_MINIMUM)));
        mockRejectedChannel();
        mockFrozenFunds(1);

        for (int repeat = 0; repeat < 2; repeat++) {
            assertEquals("PAYOUT_FAILED", service.returnBelowMinimum(ID, CHANNEL).get("status"));
        }

        verify(accounts).unfreezeFunds(1, 1, ACCOUNT, 1);
        verify(db).insertJournal(ACCOUNT, "transfer_failed_unfreeze", ID, 1, -1, null, null);
        verify(transfers).finalizeTransfer(WithdrawalTransfer.FAILED, null,
            WithdrawalLimits.RETURNED_BELOW_MINIMUM, ID);
    }

    @Test
    void unverifiedSubmissionAndWrongChannelKeepFundsFrozen() {
        when(requests.lockWithdrawal(ID)).thenReturn(List.of(row("TRANSFERRING", WithdrawalLimits.REJECTED_SUBMISSION_REASON)));
        mockRejectedChannel();
        assertThrows(ResponseStatusException.class, () -> service.returnBelowMinimum(ID, Map.of()));
        assertThrows(ResponseStatusException.class, () -> service.returnBelowMinimum(ID,
            Map.of("appId", "wrong-app", "merchantNo", "qa-merchant")));
        verifyNoInteractions(accounts);
    }

    @Test
    void anotherWithdrawalsFrozenBalanceCannotBeReturned() {
        when(requests.lockWithdrawal(ID)).thenReturn(List.of(row("TRANSFERRING", WithdrawalLimits.REJECTED_SUBMISSION_REASON)));
        mockRejectedChannel();
        mockFrozenFunds(0);
        assertThrows(ResponseStatusException.class, () -> service.returnBelowMinimum(ID, CHANNEL));
        verify(accounts, never()).unfreezeFunds(anyLong(), anyLong(), anyString(), anyLong());
    }

    @Test
    void approvedOneCentWithNoChannelIsReturnedWithoutCreatingATransfer() {
        when(requests.lockWithdrawal(ID)).thenReturn(List.of(row("PENDING_PAYOUT", "等待渠道发起")));
        mockFrozenFunds(1);
        assertThrows(ResponseStatusException.class, () -> service.prepareTransfer(ID));
        assertEquals("PAYOUT_FAILED", service.returnBelowMinimum(ID, Map.of()).get("status"));
        verify(requests).updateStatus(Withdrawal.PAYOUT_FAILED, WithdrawalLimits.RETURNED_BELOW_MINIMUM, ID);
        verify(db, never()).insertTransfer(anyString(), anyString(), any());
    }

    private void mockNewRequest(long amount, String type) {
        when(db.insertIntentIfAbsent(KEY, ACCOUNT, amount, WithdrawalIntent.PROCESSING)).thenReturn(1);
        when(accounts.lockForWithdrawal(ACCOUNT)).thenReturn(List.of(Map.of(
            "type", type, "status", "ACCOUNT_ACTIVE", "balance_cents", 500L, "name", "测试账户")));
    }

    private void mockRejectedChannel() {
        when(transfers.lockTransfer(ID)).thenReturn(List.of(Map.of("status", "PROCESSING")));
        when(db.hasRejectedRiderChannel(ID, "wx-qa", "qa-merchant")).thenReturn(true);
    }

    private void mockFrozenFunds(long amount) {
        when(accounts.lockAccount(ACCOUNT)).thenReturn(ACCOUNT);
        when(db.outstandingFrozen(ACCOUNT, ID)).thenReturn(amount);
        when(accounts.unfreezeFunds(1, 1, ACCOUNT, 1)).thenReturn(1);
    }

    private static Map<String, Object> request(long amount) {
        return Map.of("accountId", ACCOUNT, "requestKey", KEY, "amountCents", amount);
    }

    private static Map<String, Object> row(String status, String reason) {
        return Map.of("applicant_type", "RIDER", "account_id", ACCOUNT, "amount_cents", 1L,
            "status", status, "reason", reason, "reason_code", "WECHAT_SUBMISSION_ERROR");
    }
}

package com.self.multi_currency_household_ledger.scheduler;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.self.multi_currency_household_ledger.exchange.service.ExchangeRateService;
import com.self.multi_currency_household_ledger.ledger.service.LedgerRecalculationService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ExchangeRateSchedulerTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDate TODAY = LocalDate.of(2026, 4, 6);
    private static final Clock ELEVEN_O_FIVE = Clock.fixed(Instant.parse("2026-04-06T02:05:00Z"), KST);

    @Mock
    private ExchangeRateService exchangeRateService;

    @Mock
    private LedgerRecalculationService ledgerRecalculationService;

    private ExchangeRateScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new ExchangeRateScheduler(exchangeRateService, ledgerRecalculationService, ELEVEN_O_FIVE);
    }

    @Test
    @DisplayName("11:05 KST 수집 성공 후 ledger 재계산을 호출한다")
    void daily_collection_success_triggers_recalculation() {
        given(exchangeRateService.fetchAndSaveRates(TODAY)).willReturn(true);

        scheduler.collectDailyRates();

        verify(ledgerRecalculationService).recalculateRecentForeignEntries();
    }

    @Test
    @DisplayName("수집 실패 시 재계산을 건너뛰고 다음 인트라데이 성공에서 회수한다")
    void failed_daily_collection_is_recovered_by_next_successful_intraday_retry() {
        given(exchangeRateService.fetchAndSaveRates(TODAY)).willReturn(false, true);

        scheduler.collectDailyRates();
        verify(ledgerRecalculationService, never()).recalculateRecentForeignEntries();

        scheduler.retryFailedDailyCollection();

        verify(ledgerRecalculationService, times(1)).recalculateRecentForeignEntries();
    }

    @Test
    @DisplayName("대기 중인 실패가 없으면 인트라데이 재시도는 아무 작업도 하지 않는다")
    void intraday_retry_is_noop_without_pending_failure() {
        scheduler.retryFailedDailyCollection();

        verify(exchangeRateService, never()).fetchAndSaveRates(TODAY);
        verify(ledgerRecalculationService, never()).recalculateRecentForeignEntries();
    }
}

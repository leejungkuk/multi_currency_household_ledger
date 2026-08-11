package com.self.multi_currency_household_ledger.scheduler;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.self.multi_currency_household_ledger.exchange.service.ExchangeRateBackfillService;
import com.self.multi_currency_household_ledger.exchange.service.ExchangeRateBackfillService.BackfillResult;
import com.self.multi_currency_household_ledger.exchange.service.ExchangeRateBackfillService.BackfillStatus;
import com.self.multi_currency_household_ledger.ledger.service.LedgerRecalculationService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ExchangeRateSchedulerTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDate TODAY = LocalDate.of(2026, 4, 6);
    private static final int WINDOW_DAYS = 30;
    private static final LocalDate WINDOW_START = TODAY.minusDays(WINDOW_DAYS);
    private static final Clock ELEVEN_O_FIVE = Clock.fixed(Instant.parse("2026-04-06T02:05:00Z"), KST);
    private static final Clock AFTER_CUTOFF = Clock.fixed(Instant.parse("2026-04-06T05:30:00Z"), KST);

    @Mock
    private ExchangeRateBackfillService backfillService;

    @Mock
    private LedgerRecalculationService ledgerRecalculationService;

    @Mock
    private TaskScheduler taskScheduler;

    @Mock
    private ScheduledFuture<?> scheduledFuture;

    private ExchangeRateScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new ExchangeRateScheduler(
                backfillService, ledgerRecalculationService, taskScheduler, ELEVEN_O_FIVE, WINDOW_DAYS);
    }

    @Test
    @DisplayName("백필과 재계산은 한 번 샘플링한 오늘에서 나온 같은 윈도우 시작일을 받는다")
    void backfill_and_recalculation_share_window_start() {
        given(backfillService.backfill(WINDOW_START, TODAY)).willReturn(completed(true));

        scheduler.collectDailyRates();

        ArgumentCaptor<LocalDate> backfillStart = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<LocalDate> backfillEnd = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<LocalDate> recalculationStart = ArgumentCaptor.forClass(LocalDate.class);
        verify(backfillService).backfill(backfillStart.capture(), backfillEnd.capture());
        verify(ledgerRecalculationService).recalculateForeignEntriesFrom(recalculationStart.capture());
        assertEquals(backfillStart.getValue(), recalculationStart.getValue());
        assertEquals(WINDOW_START, backfillStart.getValue());
        assertEquals(TODAY, backfillEnd.getValue());
    }

    @Test
    @DisplayName("자정 경계에서도 백필 종료일과 재계산 시작일은 같은 오늘에서 파생된다")
    void midnight_boundary_uses_single_today_sample() {
        Clock crossingMidnight =
                new AdvancingClock(Instant.parse("2026-04-06T14:59:59.900Z"), Instant.parse("2026-04-06T15:00:00Z"));
        ExchangeRateScheduler crossingMidnightScheduler = new ExchangeRateScheduler(
                backfillService, ledgerRecalculationService, taskScheduler, crossingMidnight, WINDOW_DAYS);
        given(backfillService.backfill(any(LocalDate.class), any(LocalDate.class)))
                .willReturn(completed(true));

        crossingMidnightScheduler.collectDailyRates();

        ArgumentCaptor<LocalDate> backfillEnd = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<LocalDate> recalculationStart = ArgumentCaptor.forClass(LocalDate.class);
        verify(backfillService).backfill(any(LocalDate.class), backfillEnd.capture());
        verify(ledgerRecalculationService).recalculateForeignEntriesFrom(recalculationStart.capture());
        assertEquals(backfillEnd.getValue().minusDays(WINDOW_DAYS), recalculationStart.getValue());
    }

    @ParameterizedTest(name = "{0}이어도 재계산한다")
    @EnumSource(
            value = BackfillStatus.class,
            names = {"AUTH_ABORTED", "QUOTA_ABORTED", "TRANSIENT_ABORTED"})
    void aborted_backfill_still_triggers_recalculation(BackfillStatus status) {
        given(backfillService.backfill(WINDOW_START, TODAY)).willReturn(new BackfillResult(status, 1, 2, false));

        scheduler.collectDailyRates();

        verify(ledgerRecalculationService).recalculateForeignEntriesFrom(WINDOW_START);
    }

    @Test
    @DisplayName("오늘 환율이 미완결이면 다음 인트라데이 cron이 사이클을 실행한다")
    void incomplete_end_date_enables_intraday_retry() {
        given(backfillService.backfill(WINDOW_START, TODAY))
                .willReturn(completed(false))
                .willReturn(completed(true));

        scheduler.collectDailyRates();
        scheduler.retryFailedDailyCollection();

        verify(backfillService, times(2)).backfill(WINDOW_START, TODAY);
        verify(ledgerRecalculationService, times(2)).recalculateForeignEntriesFrom(WINDOW_START);
    }

    @Test
    @DisplayName("오늘 환율이 완결되면 다음 인트라데이 cron은 아무 작업도 하지 않는다")
    void complete_end_date_disables_intraday_retry() {
        given(backfillService.backfill(WINDOW_START, TODAY)).willReturn(completed(true));
        scheduler.collectDailyRates();
        clearInvocations(backfillService, ledgerRecalculationService);

        scheduler.retryFailedDailyCollection();

        verifyNoInteractions(backfillService);
        verify(ledgerRecalculationService, never()).recalculateForeignEntriesFrom(any());
    }

    @Test
    @DisplayName("동시 실행 가드로 스킵해도 기존 재시도 대기 상태를 바꾸지 않는다")
    void guard_skip_does_not_change_retry_pending() {
        running().set(true);
        retryPending().set(true);

        scheduler.collectDailyRates();

        assertTrue(retryPending().get());
        verifyNoInteractions(backfillService, ledgerRecalculationService);
    }

    @Test
    @DisplayName("백필 런타임 예외를 밖으로 던지지 않고 다음 인트라데이 cron에서 재시도한다")
    void runtime_exception_is_swallowed_and_enables_retry() {
        given(backfillService.backfill(WINDOW_START, TODAY))
                .willThrow(new IllegalStateException("provider failure"))
                .willReturn(completed(true));

        assertDoesNotThrow(scheduler::collectDailyRates);
        verify(ledgerRecalculationService, never()).recalculateForeignEntriesFrom(any());

        assertDoesNotThrow(scheduler::retryFailedDailyCollection);
        verify(backfillService, times(2)).backfill(WINDOW_START, TODAY);
        verify(ledgerRecalculationService).recalculateForeignEntriesFrom(WINDOW_START);
    }

    @Test
    @DisplayName("14시 컷오프 이후에는 재시도 대기를 내리고 수집하지 않는다")
    void retry_after_cutoff_clears_pending_without_collection() {
        ExchangeRateScheduler afterCutoff = new ExchangeRateScheduler(
                backfillService, ledgerRecalculationService, taskScheduler, AFTER_CUTOFF, WINDOW_DAYS);
        retryPending(afterCutoff).set(true);

        afterCutoff.retryFailedDailyCollection();

        assertFalse(retryPending(afterCutoff).get());
        verifyNoInteractions(backfillService);
        verify(ledgerRecalculationService, never()).recalculateForeignEntriesFrom(any());
    }

    @Test
    @DisplayName("백필 윈도우가 허용 범위 밖이면 생성에 실패한다")
    void invalid_window_days_fail_fast() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ExchangeRateScheduler(
                        backfillService, ledgerRecalculationService, taskScheduler, ELEVEN_O_FIVE, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ExchangeRateScheduler(
                        backfillService, ledgerRecalculationService, taskScheduler, ELEVEN_O_FIVE, 366));
    }

    @ParameterizedTest(name = "백필 윈도우 {0}일은 허용한다")
    @ValueSource(ints = {1, 365})
    void valid_window_day_boundaries_are_accepted(int windowDays) {
        assertDoesNotThrow(() -> new ExchangeRateScheduler(
                backfillService, ledgerRecalculationService, taskScheduler, ELEVEN_O_FIVE, windowDays));
    }

    @Test
    @DisplayName("ApplicationReadyEvent는 백필 사이클을 TaskScheduler에 예약한다")
    void application_ready_schedules_delayed_cycle() {
        given(taskScheduler.schedule(any(Runnable.class), any(Instant.class)))
                .willAnswer(invocation -> scheduledFuture);
        given(backfillService.backfill(WINDOW_START, TODAY)).willReturn(completed(true));

        scheduler.scheduleStartupBackfill();

        ArgumentCaptor<Runnable> scheduledTask = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).schedule(scheduledTask.capture(), any(Instant.class));
        verifyNoInteractions(backfillService);
        verify(ledgerRecalculationService, never()).recalculateForeignEntriesFrom(any());

        scheduledTask.getValue().run();

        verify(backfillService).backfill(WINDOW_START, TODAY);
        verify(ledgerRecalculationService).recalculateForeignEntriesFrom(WINDOW_START);
    }

    @Test
    @DisplayName("기동 백필 예약이 거부돼도 ApplicationReadyEvent 처리를 실패시키지 않는다")
    void application_ready_swallows_schedule_rejection() {
        given(taskScheduler.schedule(any(Runnable.class), any(Instant.class)))
                .willThrow(new TaskRejectedException("scheduler is shutting down"));

        assertDoesNotThrow(scheduler::scheduleStartupBackfill);
    }

    private static BackfillResult completed(boolean endDateComplete) {
        return new BackfillResult(BackfillStatus.COMPLETED, 0, 0, endDateComplete);
    }

    private AtomicBoolean running() {
        return atomicBooleanField(scheduler, "running");
    }

    private AtomicBoolean retryPending() {
        return retryPending(scheduler);
    }

    private static AtomicBoolean retryPending(ExchangeRateScheduler target) {
        return atomicBooleanField(target, "retryPending");
    }

    private static AtomicBoolean atomicBooleanField(ExchangeRateScheduler target, String name) {
        return (AtomicBoolean) ReflectionTestUtils.getField(target, name);
    }

    private static final class AdvancingClock extends Clock {

        private final Instant firstInstant;
        private final Instant laterInstant;
        private final AtomicInteger calls = new AtomicInteger();

        private AdvancingClock(Instant firstInstant, Instant laterInstant) {
            this.firstInstant = firstInstant;
            this.laterInstant = laterInstant;
        }

        @Override
        public ZoneId getZone() {
            return KST;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!KST.equals(zone)) {
                throw new IllegalArgumentException("AdvancingClock only supports Asia/Seoul");
            }
            return this;
        }

        @Override
        public Instant instant() {
            return calls.getAndIncrement() == 0 ? firstInstant : laterInstant;
        }
    }
}

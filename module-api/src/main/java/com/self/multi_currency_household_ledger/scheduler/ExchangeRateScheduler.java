package com.self.multi_currency_household_ledger.scheduler;

import com.self.multi_currency_household_ledger.exchange.service.ExchangeRateBackfillService;
import com.self.multi_currency_household_ledger.exchange.service.ExchangeRateBackfillService.BackfillResult;
import com.self.multi_currency_household_ledger.ledger.service.LedgerRecalculationService;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class ExchangeRateScheduler {

    private static final LocalTime RETRY_CUTOFF_TIME = LocalTime.of(14, 0);
    private static final Duration STARTUP_BACKFILL_DELAY = Duration.ofSeconds(30);

    private final ExchangeRateBackfillService backfillService;
    private final LedgerRecalculationService ledgerRecalculationService;
    private final TaskScheduler taskScheduler;
    private final Clock clock;
    private final int windowDays;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean retryPending = new AtomicBoolean(false);

    public ExchangeRateScheduler(
            ExchangeRateBackfillService backfillService,
            LedgerRecalculationService ledgerRecalculationService,
            TaskScheduler taskScheduler,
            Clock clock,
            @Value("${exchange.backfill.window-days:30}") int windowDays) {
        validateWindowDays(windowDays);
        this.backfillService = backfillService;
        this.ledgerRecalculationService = ledgerRecalculationService;
        this.taskScheduler = taskScheduler;
        this.clock = clock;
        this.windowDays = windowDays;
    }

    @Scheduled(cron = "0 5 11 * * *", zone = "Asia/Seoul")
    public void collectDailyRates() {
        runCollectionCycle();
    }

    @Scheduled(cron = "0 0,30 11-14 * * *", zone = "Asia/Seoul")
    public void retryFailedDailyCollection() {
        if (!retryPending.get()) {
            return;
        }
        if (LocalTime.now(clock).isAfter(RETRY_CUTOFF_TIME)) {
            retryPending.set(false);
            log.warn("환율 인트라데이 재시도 종료. cutoff={}", RETRY_CUTOFF_TIME);
            return;
        }
        runCollectionCycle();
    }

    public void scheduleStartupBackfill() {
        try {
            var unused = taskScheduler.schedule(
                    this::runCollectionCycle, clock.instant().plus(STARTUP_BACKFILL_DELAY));
        } catch (RuntimeException e) {
            log.error("기동 후 환율 백필 예약에 실패했습니다. 다음 정기 수집에서 다시 시도합니다.", e);
        }
    }

    private void runCollectionCycle() {
        if (!running.compareAndSet(false, true)) {
            log.debug("환율 백필·재계산 사이클이 이미 실행 중이라 건너뜁니다.");
            return;
        }

        try {
            LocalDate today = LocalDate.now(clock);
            LocalDate windowStart = today.minusDays(windowDays);
            BackfillResult result = backfillService.backfill(windowStart, today);
            logBackfillResult(result, windowStart);
            int recalculated = ledgerRecalculationService.recalculateForeignEntriesFrom(windowStart);
            log.info(
                    "환율 거래 재계산 완료. recalculated={}, endDateComplete={}, windowStart={}",
                    recalculated,
                    result.endDateComplete(),
                    windowStart);
            retryPending.set(!result.endDateComplete());
        } catch (RuntimeException e) {
            retryPending.set(true);
            log.error("환율 백필 또는 거래 재계산 스케줄 실패. windowDays={}", windowDays, e);
        } finally {
            running.set(false);
        }
    }

    private void logBackfillResult(BackfillResult result, LocalDate windowStart) {
        switch (result.status()) {
            case COMPLETED ->
                log.info(
                        "환율 백필 사이클 완료. status={}, filledDays={}, failedDays={}, windowStart={}",
                        result.status(),
                        result.filledDays(),
                        result.failedDays(),
                        windowStart);
            case QUOTA_ABORTED ->
                log.warn(
                        "환율 백필 사이클 중단. status={}, filledDays={}, failedDays={}, windowStart={}",
                        result.status(),
                        result.filledDays(),
                        result.failedDays(),
                        windowStart);
            case AUTH_ABORTED, TRANSIENT_ABORTED ->
                log.error(
                        "환율 백필 사이클 중단. status={}, filledDays={}, failedDays={}, windowStart={}",
                        result.status(),
                        result.filledDays(),
                        result.failedDays(),
                        windowStart);
        }
    }

    private static void validateWindowDays(int windowDays) {
        if (windowDays < 1 || windowDays > 365) {
            throw new IllegalArgumentException("exchange.backfill.window-days must be between 1 and 365");
        }
    }
}

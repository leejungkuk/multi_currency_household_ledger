package com.self.multi_currency_household_ledger.scheduler;

import com.self.multi_currency_household_ledger.exchange.service.ExchangeRateService;
import com.self.multi_currency_household_ledger.ledger.service.LedgerRecalculationService;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ExchangeRateScheduler {

    private static final LocalTime RETRY_CUTOFF_TIME = LocalTime.of(14, 0);
    // Step 2에서 exchange.backfill.window-days 프로퍼티로 대체한다.
    private static final int TEMPORARY_BACKFILL_WINDOW_DAYS = 30;

    private final ExchangeRateService exchangeRateService;
    private final LedgerRecalculationService ledgerRecalculationService;
    private final Clock clock;
    private final AtomicBoolean retryPending = new AtomicBoolean(false);

    @Scheduled(cron = "0 5 11 * * *", zone = "Asia/Seoul")
    public void collectDailyRates() {
        collectAndRecalculate(LocalDate.now(clock));
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
        collectAndRecalculate(LocalDate.now(clock));
    }

    private void collectAndRecalculate(LocalDate date) {
        try {
            int saved = exchangeRateService.fetchAndSaveRates(date);
            LocalDate windowStart = LocalDate.now(clock).minusDays(TEMPORARY_BACKFILL_WINDOW_DAYS);
            int recalculated = ledgerRecalculationService.recalculateForeignEntriesFrom(windowStart);
            retryPending.set(false);
            log.info("환율 수집 후 거래 재계산 완료. date={}, saved={}, recalculated={}", date, saved, recalculated);
        } catch (RuntimeException e) {
            retryPending.set(true);
            log.error("환율 수집 또는 거래 재계산 스케줄 실패. date={}", date, e);
        }
    }
}

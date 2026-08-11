package com.self.multi_currency_household_ledger.scheduler;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
// 운영 재기동 회수 경로로 한정해 로컬·테스트 컨텍스트의 실제 provider 호출과 DB 재계산을 막는다.
@Profile("prod")
public class ExchangeRateStartupBackfillListener {

    private final ExchangeRateScheduler scheduler;

    public ExchangeRateStartupBackfillListener(ExchangeRateScheduler scheduler) {
        this.scheduler = scheduler;
    }

    @EventListener(ApplicationReadyEvent.class)
    void scheduleStartupBackfill() {
        scheduler.scheduleStartupBackfill();
    }
}

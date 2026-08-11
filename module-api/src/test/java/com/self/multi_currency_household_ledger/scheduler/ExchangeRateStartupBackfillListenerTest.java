package com.self.multi_currency_household_ledger.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ExchangeRateStartupBackfillListenerTest {

    private final ExchangeRateScheduler scheduler = mock(ExchangeRateScheduler.class);

    @Test
    void startup_listener_is_not_registered_without_profile() {
        startupListenerContext()
                .run(context -> assertEquals(
                        0,
                        context.getBeansOfType(ExchangeRateStartupBackfillListener.class)
                                .size()));
    }

    @ParameterizedTest(name = "{0} 프로파일의 기동 백필 리스너 수는 {1}개다")
    @CsvSource(
            value = {"local|0", "prod|1", "prod,local|1"},
            delimiter = '|')
    void startup_listener_is_registered_only_when_prod_is_active(String profile, int expectedCount) {
        startupListenerContext()
                .withPropertyValues("spring.profiles.active=" + profile)
                .run(context -> assertEquals(
                        expectedCount,
                        context.getBeansOfType(ExchangeRateStartupBackfillListener.class)
                                .size()));
    }

    @Test
    void application_ready_event_delegates_startup_backfill_to_scheduler_in_prod() {
        startupListenerContext()
                .withPropertyValues("spring.profiles.active=prod")
                .run(context -> {
                    context.publishEvent(new ApplicationReadyEvent(
                            new SpringApplication(ExchangeRateStartupBackfillListener.class),
                            new String[0],
                            context,
                            Duration.ZERO));

                    verify(scheduler).scheduleStartupBackfill();
                });
    }

    private ApplicationContextRunner startupListenerContext() {
        return new ApplicationContextRunner()
                .withBean(ExchangeRateScheduler.class, () -> scheduler)
                .withUserConfiguration(ExchangeRateStartupBackfillListener.class);
    }
}

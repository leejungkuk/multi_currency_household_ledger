package com.self.multi_currency_household_ledger.scheduler;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.times;

import com.self.multi_currency_household_ledger.member.service.AnonymousAccountCleanupService;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class AnonymousAccountCleanupSchedulerTest {

    @Mock
    private AnonymousAccountCleanupService cleanupService;

    private AnonymousAccountCleanupScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new AnonymousAccountCleanupScheduler(cleanupService);
    }

    @Test
    @DisplayName("스케줄러는 정책을 갖지 않고 정리 서비스를 호출만 한다")
    void delegates_to_the_cleanup_service_without_policy() {
        scheduler.cleanupAbandonedAnonymousAccounts();

        then(cleanupService).should().cleanupAbandonedAnonymousAccounts();
        then(cleanupService).shouldHaveNoMoreInteractions();
    }

    @Test
    @DisplayName("동시 실행 가드가 켜져 있으면 이번 주기를 건너뛴다")
    void skips_the_cycle_while_a_previous_run_is_still_in_flight() {
        running().set(true);

        scheduler.cleanupAbandonedAnonymousAccounts();

        then(cleanupService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("런타임 예외를 밖으로 던지지 않고 가드를 풀어 다음 주기를 막지 않는다")
    void runtime_exception_is_swallowed_and_releases_the_guard() {
        willThrow(new IllegalStateException("cleanup failure"))
                .willDoNothing()
                .given(cleanupService)
                .cleanupAbandonedAnonymousAccounts();

        assertDoesNotThrow(scheduler::cleanupAbandonedAnonymousAccounts);
        scheduler.cleanupAbandonedAnonymousAccounts();

        then(cleanupService).should(times(2)).cleanupAbandonedAnonymousAccounts();
    }

    private AtomicBoolean running() {
        return (AtomicBoolean) ReflectionTestUtils.getField(scheduler, "running");
    }
}

package com.self.multi_currency_household_ledger.member.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import com.self.multi_currency_household_ledger.member.repository.AuthUserRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

@ExtendWith(MockitoExtension.class)
class AnonymousAccountCleanupServiceTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final Instant NOW = Instant.parse("2026-08-23T19:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, KST);
    private static final int RETENTION_DAYS = 365;
    private static final int BATCH_LIMIT = 500;
    private static final Instant CUTOFF_INSTANT = NOW.minus(RETENTION_DAYS, ChronoUnit.DAYS);
    private static final LocalDateTime CUTOFF_LOCAL = LocalDateTime.ofInstant(CUTOFF_INSTANT, ZoneId.systemDefault());
    private static final UUID FIRST = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SECOND = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Mock
    private AuthUserRepository authUserRepository;

    @Mock
    private PlatformTransactionManager transactionManager;

    @Mock
    private TransactionStatus transactionStatus;

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    @Test
    @DisplayName("관측 전용 모드는 후보 조회와 카운트만 하고 삭제는 한 건도 하지 않는다")
    void observation_mode_counts_candidates_without_deleting() {
        given(authUserRepository.findAbandonedAnonymousIds(CUTOFF_INSTANT, CUTOFF_LOCAL, BATCH_LIMIT))
                .willReturn(List.of(FIRST, SECOND));

        service(false, BATCH_LIMIT).cleanupAbandonedAnonymousAccounts();

        then(authUserRepository).should().findAbandonedAnonymousIds(CUTOFF_INSTANT, CUTOFF_LOCAL, BATCH_LIMIT);
        then(authUserRepository).should(never()).deleteAbandonedAnonymousUser(any(), any(), any());
        then(transactionManager).shouldHaveNoInteractions();
        assertThat(counter("candidate")).isEqualTo(2.0);
        assertThat(counter("disabled")).isEqualTo(1.0);
        assertThat(counter("deleted")).isZero();
    }

    @Test
    @DisplayName("cutoff 두 개를 Clock에서 파생하고 batch-limit을 후보 조회에 그대로 전달한다")
    void derives_two_cutoffs_from_clock_and_passes_batch_limit() {
        given(authUserRepository.findAbandonedAnonymousIds(any(), any(), anyInt()))
                .willReturn(List.of());

        service(false, 7).cleanupAbandonedAnonymousAccounts();

        // auth.* 는 timestamptz(:cutoffInstant), ledger_entry·category 의 감사 컬럼은 JVM 기본 존으로 기록된
        // naive timestamp(:cutoffLocal) 이라 하나로 통일하면 세션 TimeZone 에 따라 조용히 어긋난다.
        then(authUserRepository).should().findAbandonedAnonymousIds(CUTOFF_INSTANT, CUTOFF_LOCAL, 7);
    }

    @Test
    @DisplayName("한 계정의 삭제가 실패해도 롤백 후 다음 계정으로 계속 진행한다")
    void continues_to_next_account_when_one_deletion_fails() {
        givenTransaction();
        given(authUserRepository.findAbandonedAnonymousIds(CUTOFF_INSTANT, CUTOFF_LOCAL, BATCH_LIMIT))
                .willReturn(List.of(FIRST, SECOND));
        given(authUserRepository.deleteAbandonedAnonymousUser(FIRST, CUTOFF_INSTANT, CUTOFF_LOCAL))
                .willThrow(new IllegalStateException("delete failed"));
        given(authUserRepository.deleteAbandonedAnonymousUser(SECOND, CUTOFF_INSTANT, CUTOFF_LOCAL))
                .willReturn(1);

        assertThatCode(() -> service(true, BATCH_LIMIT).cleanupAbandonedAnonymousAccounts())
                .doesNotThrowAnyException();

        then(authUserRepository).should().deleteAbandonedAnonymousUser(SECOND, CUTOFF_INSTANT, CUTOFF_LOCAL);
        then(transactionManager).should().rollback(transactionStatus);
        assertThat(counter("error")).isEqualTo(1.0);
        assertThat(counter("deleted")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("후보 조회와 삭제 사이에 활동이 생겨 0행이 삭제되면 skipped로 관측된다")
    void zero_row_delete_is_observed_as_skipped() {
        givenTransaction();
        given(authUserRepository.findAbandonedAnonymousIds(CUTOFF_INSTANT, CUTOFF_LOCAL, BATCH_LIMIT))
                .willReturn(List.of(FIRST));
        given(authUserRepository.deleteAbandonedAnonymousUser(FIRST, CUTOFF_INSTANT, CUTOFF_LOCAL))
                .willReturn(0);

        service(true, BATCH_LIMIT).cleanupAbandonedAnonymousAccounts();

        assertThat(counter("skipped")).isEqualTo(1.0);
        assertThat(counter("deleted")).isZero();
    }

    @Test
    @DisplayName("삭제 카운터는 계정별 트랜잭션이 모두 커밋된 뒤에 증가한다")
    void increments_deleted_counter_only_after_commit() {
        givenTransaction();
        willAnswer(invocation -> {
                    assertThat(counter("deleted")).isZero();
                    return null;
                })
                .given(transactionManager)
                .commit(transactionStatus);
        given(authUserRepository.findAbandonedAnonymousIds(CUTOFF_INSTANT, CUTOFF_LOCAL, BATCH_LIMIT))
                .willReturn(List.of(FIRST, SECOND));
        given(authUserRepository.deleteAbandonedAnonymousUser(any(), any(), any()))
                .willReturn(1);

        service(true, BATCH_LIMIT).cleanupAbandonedAnonymousAccounts();

        then(transactionManager).should(times(2)).commit(transactionStatus);
        assertThat(counter("deleted")).isEqualTo(2.0);
    }

    @ParameterizedTest
    @ValueSource(ints = {29, 3651})
    @DisplayName("retention-days가 30~3650 밖이면 기동 시점에 거부한다")
    void rejects_retention_days_out_of_range(int retentionDays) {
        assertThatThrownBy(() -> new AnonymousAccountCleanupService(
                        authUserRepository, meterRegistry, transactionManager, CLOCK, true, retentionDays, BATCH_LIMIT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {30, 3650})
    @DisplayName("retention-days 경계값은 받아들인다")
    void accepts_retention_days_boundaries(int retentionDays) {
        assertThatCode(() -> new AnonymousAccountCleanupService(
                        authUserRepository, meterRegistry, transactionManager, CLOCK, true, retentionDays, BATCH_LIMIT))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 10001})
    @DisplayName("batch-limit이 1~10000 밖이면 기동 시점에 거부한다")
    void rejects_batch_limit_out_of_range(int batchLimit) {
        // 0 은 예외 없이 조용히 망가진다: Postgres 의 limit 0 은 에러 없이 0행을 주고(매일 무음 no-op),
        // 동시에 candidates.size() >= batchLimit 이 0 >= 0 으로 참이라 "배치 상한 도달" 경고를 매일 띄운다.
        assertThatThrownBy(() -> new AnonymousAccountCleanupService(
                        authUserRepository, meterRegistry, transactionManager, CLOCK, true, RETENTION_DAYS, batchLimit))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 10000})
    @DisplayName("batch-limit 경계값은 받아들인다")
    void accepts_batch_limit_boundaries(int batchLimit) {
        assertThatCode(() -> new AnonymousAccountCleanupService(
                        authUserRepository, meterRegistry, transactionManager, CLOCK, true, RETENTION_DAYS, batchLimit))
                .doesNotThrowAnyException();
    }

    private void givenTransaction() {
        given(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .willReturn(transactionStatus);
    }

    private AnonymousAccountCleanupService service(boolean enabled, int batchLimit) {
        return new AnonymousAccountCleanupService(
                authUserRepository, meterRegistry, transactionManager, CLOCK, enabled, RETENTION_DAYS, batchLimit);
    }

    private double counter(String result) {
        var counter = meterRegistry
                .find("woni.member.anonymous_cleanup")
                .tag("result", result)
                .counter();
        return counter == null ? 0.0 : counter.count();
    }
}

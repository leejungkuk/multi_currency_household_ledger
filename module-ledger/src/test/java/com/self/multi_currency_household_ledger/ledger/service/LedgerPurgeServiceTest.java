package com.self.multi_currency_household_ledger.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;

import com.self.multi_currency_household_ledger.ledger.domain.LedgerEntryRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

@ExtendWith(MockitoExtension.class)
class LedgerPurgeServiceTest {

    private static final UUID MEMBER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Mock
    private LedgerEntryRepository ledgerEntryRepository;

    @Mock
    private PlatformTransactionManager transactionManager;

    @Mock
    private TransactionStatus transactionStatus;

    private SimpleMeterRegistry meterRegistry;
    private LedgerPurgeService ledgerPurgeService;

    @BeforeEach
    void setUp() {
        given(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .willReturn(transactionStatus);
        meterRegistry = new SimpleMeterRegistry();
        ledgerPurgeService = new LedgerPurgeService(ledgerEntryRepository, meterRegistry, transactionManager);
    }

    @Test
    @DisplayName("회원 거래가 삭제되면 member_id를 전달하고 deleted 카운터를 증가시킨다")
    void purge_increments_deleted_counter_when_rows_are_deleted() {
        given(ledgerEntryRepository.deleteAllByMemberId(MEMBER_ID)).willReturn(2);

        ledgerPurgeService.purge(MEMBER_ID);

        then(ledgerEntryRepository).should().deleteAllByMemberId(MEMBER_ID);
        assertThat(counter("deleted")).isEqualTo(1.0);
        assertThat(counter("noop")).isZero();
    }

    @Test
    @DisplayName("삭제할 거래가 없어도 예외 없이 noop 카운터를 증가시킨다")
    void purge_is_idempotent_and_increments_noop_counter_when_no_rows_are_deleted() {
        given(ledgerEntryRepository.deleteAllByMemberId(MEMBER_ID)).willReturn(0);

        assertThatCode(() -> ledgerPurgeService.purge(MEMBER_ID)).doesNotThrowAnyException();

        then(ledgerEntryRepository).should().deleteAllByMemberId(MEMBER_ID);
        assertThat(counter("deleted")).isZero();
        assertThat(counter("noop")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("삭제가 실패하면 예외를 전파하고 카운터를 증가시키지 않는다")
    void purge_does_not_increment_counters_when_delete_fails() {
        IllegalStateException failure = new IllegalStateException("delete failed");
        given(ledgerEntryRepository.deleteAllByMemberId(MEMBER_ID)).willThrow(failure);

        assertThatThrownBy(() -> ledgerPurgeService.purge(MEMBER_ID)).isSameAs(failure);

        assertThat(counter("deleted")).isZero();
        assertThat(counter("noop")).isZero();
    }

    @Test
    @DisplayName("트랜잭션 커밋이 실패하면 예외를 전파하고 카운터를 증가시키지 않는다")
    void purge_does_not_increment_counters_when_commit_fails() {
        IllegalStateException failure = new IllegalStateException("commit failed");
        given(ledgerEntryRepository.deleteAllByMemberId(MEMBER_ID)).willReturn(1);
        willThrow(failure).given(transactionManager).commit(transactionStatus);

        assertThatThrownBy(() -> ledgerPurgeService.purge(MEMBER_ID)).isSameAs(failure);

        then(ledgerEntryRepository).should().deleteAllByMemberId(MEMBER_ID);
        assertThat(counter("deleted")).isZero();
        assertThat(counter("noop")).isZero();
    }

    private double counter(String result) {
        var counter =
                meterRegistry.find("woni.ledger.purge").tag("result", result).counter();
        return counter == null ? 0.0 : counter.count();
    }
}

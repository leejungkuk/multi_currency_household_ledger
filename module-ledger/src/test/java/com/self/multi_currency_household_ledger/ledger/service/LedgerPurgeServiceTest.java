package com.self.multi_currency_household_ledger.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;

import com.self.multi_currency_household_ledger.ledger.domain.CategoryRepository;
import com.self.multi_currency_household_ledger.ledger.domain.LedgerEntryRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
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
    private CategoryRepository categoryRepository;

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
        ledgerPurgeService =
                new LedgerPurgeService(ledgerEntryRepository, categoryRepository, meterRegistry, transactionManager);
    }

    @Test
    @DisplayName("거래를 먼저 지우고 커스텀 카테고리를 지운 뒤 deleted 카운터를 증가시킨다")
    void purge_deletes_entries_before_categories_and_increments_deleted_counter() {
        given(ledgerEntryRepository.deleteAllByMemberId(MEMBER_ID)).willReturn(2);
        given(categoryRepository.deleteAllByOwnerMemberId(MEMBER_ID)).willReturn(1);

        ledgerPurgeService.purge(MEMBER_ID);

        InOrder inOrder = inOrder(ledgerEntryRepository, categoryRepository);
        then(ledgerEntryRepository).should(inOrder).deleteAllByMemberId(MEMBER_ID);
        then(categoryRepository).should(inOrder).deleteAllByOwnerMemberId(MEMBER_ID);
        assertThat(counter("deleted")).isEqualTo(1.0);
        assertThat(counter("noop")).isZero();
    }

    @Test
    @DisplayName("지울 거래가 없고 커스텀 카테고리만 남아 있어도 deleted 카운터를 증가시킨다")
    void purge_increments_deleted_counter_when_only_categories_are_deleted() {
        given(ledgerEntryRepository.deleteAllByMemberId(MEMBER_ID)).willReturn(0);
        given(categoryRepository.deleteAllByOwnerMemberId(MEMBER_ID)).willReturn(1);

        ledgerPurgeService.purge(MEMBER_ID);

        assertThat(counter("deleted")).isEqualTo(1.0);
        assertThat(counter("noop")).isZero();
    }

    @Test
    @DisplayName("삭제할 거래도 커스텀 카테고리도 없으면 예외 없이 noop 카운터를 증가시킨다")
    void purge_is_idempotent_and_increments_noop_counter_when_no_rows_are_deleted() {
        given(ledgerEntryRepository.deleteAllByMemberId(MEMBER_ID)).willReturn(0);
        given(categoryRepository.deleteAllByOwnerMemberId(MEMBER_ID)).willReturn(0);

        assertThatCode(() -> ledgerPurgeService.purge(MEMBER_ID)).doesNotThrowAnyException();

        then(ledgerEntryRepository).should().deleteAllByMemberId(MEMBER_ID);
        then(categoryRepository).should().deleteAllByOwnerMemberId(MEMBER_ID);
        assertThat(counter("deleted")).isZero();
        assertThat(counter("noop")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("거래 삭제가 실패하면 카테고리를 지우지 않고 예외를 전파하며 카운터를 증가시키지 않는다")
    void purge_does_not_increment_counters_when_delete_fails() {
        IllegalStateException failure = new IllegalStateException("delete failed");
        given(ledgerEntryRepository.deleteAllByMemberId(MEMBER_ID)).willThrow(failure);

        assertThatThrownBy(() -> ledgerPurgeService.purge(MEMBER_ID)).isSameAs(failure);

        then(categoryRepository).shouldHaveNoInteractions();
        assertThat(counter("deleted")).isZero();
        assertThat(counter("noop")).isZero();
    }

    @Test
    @DisplayName("카테고리 삭제가 FK 위반으로 실패하면 예외를 전파하고 카운터를 증가시키지 않는다")
    void purge_does_not_increment_counters_when_category_delete_fails() {
        IllegalStateException failure = new IllegalStateException("fk_ledger_category violated");
        given(ledgerEntryRepository.deleteAllByMemberId(MEMBER_ID)).willReturn(1);
        given(categoryRepository.deleteAllByOwnerMemberId(MEMBER_ID)).willThrow(failure);

        assertThatThrownBy(() -> ledgerPurgeService.purge(MEMBER_ID)).isSameAs(failure);

        assertThat(counter("deleted")).isZero();
        assertThat(counter("noop")).isZero();
    }

    @Test
    @DisplayName("트랜잭션 커밋이 실패하면 예외를 전파하고 카운터를 증가시키지 않는다")
    void purge_does_not_increment_counters_when_commit_fails() {
        IllegalStateException failure = new IllegalStateException("commit failed");
        given(ledgerEntryRepository.deleteAllByMemberId(MEMBER_ID)).willReturn(1);
        given(categoryRepository.deleteAllByOwnerMemberId(MEMBER_ID)).willReturn(1);
        willThrow(failure).given(transactionManager).commit(transactionStatus);

        assertThatThrownBy(() -> ledgerPurgeService.purge(MEMBER_ID)).isSameAs(failure);

        then(ledgerEntryRepository).should().deleteAllByMemberId(MEMBER_ID);
        then(categoryRepository).should().deleteAllByOwnerMemberId(MEMBER_ID);
        assertThat(counter("deleted")).isZero();
        assertThat(counter("noop")).isZero();
    }

    private double counter(String result) {
        var counter =
                meterRegistry.find("woni.ledger.purge").tag("result", result).counter();
        return counter == null ? 0.0 : counter.count();
    }
}

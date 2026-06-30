package com.self.multi_currency_household_ledger.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;

import com.self.multi_currency_household_ledger.exchange.domain.CurrencyCode;
import com.self.multi_currency_household_ledger.exchange.domain.ExchangeRate;
import com.self.multi_currency_household_ledger.exchange.service.ExchangeRateService;
import com.self.multi_currency_household_ledger.ledger.TestJpaConfig;
import com.self.multi_currency_household_ledger.ledger.TestLedgerApplication;
import com.self.multi_currency_household_ledger.ledger.domain.LedgerEntry;
import com.self.multi_currency_household_ledger.ledger.domain.LedgerEntryRepository;
import com.self.multi_currency_household_ledger.ledger.dto.SyncLedgerEntryRequest;
import com.self.multi_currency_household_ledger.ledger.dto.SyncLedgerEntryResponse;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@Import({
    TestLedgerApplication.class,
    TestJpaConfig.class,
    LedgerService.class,
    LedgerSyncInsertService.class,
    LedgerSyncServiceIntegrationTest.ClockConfig.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class LedgerSyncServiceIntegrationTest {

    private static final UUID MEMBER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OTHER_MEMBER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final LocalDate TODAY = LocalDate.of(2026, 4, 6);

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private LedgerSyncInsertService ledgerSyncInsertService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @MockitoBean
    private ExchangeRateService exchangeRateService;

    @BeforeEach
    void setUp() {
        ledgerEntryRepository.deleteAll();
    }

    @Test
    @DisplayName("신규 sync upsert는 서버 환율로 외화 거래를 재해석하고 clientEntryId 매핑을 반환한다")
    void sync_creates_new_entry_with_server_reinterpreted_foreign_rate() {
        UUID clientEntryId = UUID.fromString("10000000-0000-0000-0000-000000000101");
        ExchangeRate serverRate = ExchangeRate.of(CurrencyCode.USD, new BigDecimal("1320.000000"), TODAY);
        given(exchangeRateService.getRateOnOrBefore(CurrencyCode.USD, TODAY)).willReturn(serverRate);

        SyncLedgerEntryResponse response = ledgerService.sync(
                request(clientEntryId, new BigDecimal("100.00"), CurrencyCode.USD, TODAY, "  점심  "), MEMBER_ID);

        assertThat(response.clientEntryId()).isEqualTo(clientEntryId);
        assertThat(response.ledgerEntry().id()).isNotNull();
        assertThat(response.ledgerEntry().appliedRate()).isEqualByComparingTo(new BigDecimal("1320.000000"));
        assertThat(response.ledgerEntry().krwAmount()).isEqualByComparingTo(new BigDecimal("132000.00"));
        assertThat(response.ledgerEntry().rateBaseDate()).isEqualTo(TODAY);
        assertThat(response.ledgerEntry().memo()).isEqualTo("점심");

        LedgerEntry saved = ledgerEntryRepository
                .findByMemberIdAndClientEntryId(MEMBER_ID, clientEntryId)
                .orElseThrow();
        assertThat(saved.getClientPayloadHash()).isNull();
        then(exchangeRateService).should().getRateOnOrBefore(CurrencyCode.USD, TODAY);
    }

    @Test
    @DisplayName("같은 clientEntryId의 다른 payload sync는 409 없이 기존 행을 전체 교체한다")
    void sync_updates_existing_entry_when_payload_changes() {
        UUID clientEntryId = UUID.fromString("10000000-0000-0000-0000-000000000102");
        SyncLedgerEntryResponse first = ledgerService.sync(
                request(clientEntryId, new BigDecimal("1000.00"), CurrencyCode.KRW, TODAY, "첫 값"), MEMBER_ID);

        SyncLedgerEntryResponse second = ledgerService.sync(
                request(clientEntryId, new BigDecimal("2000.00"), CurrencyCode.KRW, TODAY.plusDays(1), "변경 값"),
                MEMBER_ID);

        assertThat(second.ledgerEntry().id()).isEqualTo(first.ledgerEntry().id());
        assertThat(second.ledgerEntry().originalAmount()).isEqualByComparingTo(new BigDecimal("2000.00"));
        assertThat(second.ledgerEntry().transactionDate()).isEqualTo(TODAY.plusDays(1));
        assertThat(second.ledgerEntry().memo()).isEqualTo("변경 값");
        assertThat(ledgerEntryRepository.count()).isEqualTo(1);

        LedgerEntry saved = ledgerEntryRepository
                .findByMemberIdAndClientEntryId(MEMBER_ID, clientEntryId)
                .orElseThrow();
        assertThat(saved.getOriginalAmount()).isEqualByComparingTo(new BigDecimal("2000.00"));
        assertThat(saved.getClientEntryId()).isEqualTo(clientEntryId);
        assertThat(saved.getClientPayloadHash()).isNull();
    }

    @Test
    @DisplayName("sync upsert는 같은 clientEntryId도 member_id로 격리해 각자 별도 행을 갱신한다")
    void sync_scopes_same_client_entry_id_by_member() {
        UUID sharedClientEntryId = UUID.fromString("10000000-0000-0000-0000-000000000103");

        ledgerService.sync(
                request(sharedClientEntryId, new BigDecimal("1000.00"), CurrencyCode.KRW, TODAY, "memberA"), MEMBER_ID);
        ledgerService.sync(
                request(sharedClientEntryId, new BigDecimal("2000.00"), CurrencyCode.KRW, TODAY, "memberB"),
                OTHER_MEMBER_ID);
        ledgerService.sync(
                request(sharedClientEntryId, new BigDecimal("3000.00"), CurrencyCode.KRW, TODAY, "memberB 수정"),
                OTHER_MEMBER_ID);

        assertThat(ledgerEntryRepository.count()).isEqualTo(2);
        assertThat(ledgerEntryRepository
                        .findByMemberIdAndClientEntryId(MEMBER_ID, sharedClientEntryId)
                        .orElseThrow()
                        .getMemo())
                .isEqualTo("memberA");
        LedgerEntry memberBEntry = ledgerEntryRepository
                .findByMemberIdAndClientEntryId(OTHER_MEMBER_ID, sharedClientEntryId)
                .orElseThrow();
        assertThat(memberBEntry.getMemo()).isEqualTo("memberB 수정");
        assertThat(memberBEntry.getOriginalAmount()).isEqualByComparingTo(new BigDecimal("3000.00"));
    }

    @Test
    @DisplayName("sync delete는 member_id와 clientEntryId가 일치하는 기존 거래를 hard-delete한다")
    void delete_synced_entry_removes_existing_entry_scoped_by_member() {
        UUID clientEntryId = UUID.fromString("10000000-0000-0000-0000-000000000201");
        ledgerService.sync(
                request(clientEntryId, new BigDecimal("1000.00"), CurrencyCode.KRW, TODAY, "삭제 대상"), MEMBER_ID);
        assertThat(ledgerEntryRepository.findByMemberIdAndClientEntryId(MEMBER_ID, clientEntryId))
                .isPresent();

        ledgerService.deleteSyncedEntry(clientEntryId, MEMBER_ID);

        assertThat(ledgerEntryRepository.findByMemberIdAndClientEntryId(MEMBER_ID, clientEntryId))
                .isEmpty();
        assertThat(ledgerEntryRepository.count()).isZero();
    }

    @Test
    @DisplayName("sync delete는 대상 clientEntryId가 없어도 멱등 성공한다")
    void delete_synced_entry_succeeds_when_entry_is_absent() {
        UUID missingClientEntryId = UUID.fromString("10000000-0000-0000-0000-000000000202");

        ledgerService.deleteSyncedEntry(missingClientEntryId, MEMBER_ID);

        assertThat(ledgerEntryRepository.count()).isZero();
    }

    @Test
    @DisplayName("sync delete는 같은 clientEntryId라도 다른 member_id의 거래를 삭제하지 않고 멱등 성공한다")
    void delete_synced_entry_does_not_delete_other_members_entry() {
        UUID clientEntryId = UUID.fromString("10000000-0000-0000-0000-000000000203");
        ledgerService.sync(
                request(clientEntryId, new BigDecimal("1000.00"), CurrencyCode.KRW, TODAY, "memberA"), MEMBER_ID);

        ledgerService.deleteSyncedEntry(clientEntryId, OTHER_MEMBER_ID);

        assertThat(ledgerEntryRepository.findByMemberIdAndClientEntryId(MEMBER_ID, clientEntryId))
                .isPresent()
                .get()
                .extracting(LedgerEntry::getMemo)
                .isEqualTo("memberA");
        assertThat(ledgerEntryRepository.findByMemberIdAndClientEntryId(OTHER_MEMBER_ID, clientEntryId))
                .isEmpty();
        assertThat(ledgerEntryRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("동시 sync insert 경합은 REQUIRES_NEW 격리로 한 행에 수렴하고 500 없이 마지막 교체로 정리된다")
    void sync_concurrent_insert_race_converges_to_single_row_without_leaking_500() throws Exception {
        UUID sharedClientEntryId = UUID.fromString("10000000-0000-0000-0000-000000000104");
        LocalDate losingDate = TODAY;
        ExchangeRate losingRate = ExchangeRate.of(CurrencyCode.USD, new BigDecimal("1300.000000"), losingDate);
        CountDownLatch losingReachedRateLookup = new CountDownLatch(1);
        CountDownLatch winningCommitted = new CountDownLatch(1);

        // 패자 스레드: create() 안의 환율 조회 시점에 멈춰 승자가 같은 clientEntryId 를 먼저 커밋하게 한 뒤 풀린다.
        // 풀린 saveAndFlush 는 부분 unique 위반 → createSyncedEntry catch → 재조회→교체로 수렴해야 하고 예외가 새면 안 된다.
        given(exchangeRateService.getRateOnOrBefore(CurrencyCode.USD, losingDate))
                .willAnswer(invocation -> {
                    losingReachedRateLookup.countDown();
                    assertThat(winningCommitted.await(5, TimeUnit.SECONDS)).isTrue();
                    return losingRate;
                });

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            CompletableFuture<Throwable> losingResult = CompletableFuture.supplyAsync(
                    () -> {
                        try {
                            ledgerService.sync(
                                    request(
                                            sharedClientEntryId,
                                            new BigDecimal("10.00"),
                                            CurrencyCode.USD,
                                            losingDate,
                                            "경합 패자"),
                                    MEMBER_ID);
                            return null;
                        } catch (Throwable throwable) {
                            return throwable;
                        }
                    },
                    executor);
            assertThat(losingReachedRateLookup.await(5, TimeUnit.SECONDS)).isTrue();

            // 승자(메인): 같은 clientEntryId 를 KRW(환율 조회 없음)로 먼저 insert·commit 한다.
            SyncLedgerEntryResponse winning = ledgerService.sync(
                    request(sharedClientEntryId, new BigDecimal("5000.00"), CurrencyCode.KRW, TODAY, "경합 승자"),
                    MEMBER_ID);
            Long winningEntryId = winning.ledgerEntry().id();
            winningCommitted.countDown();

            Throwable thrown = losingResult.get(5, TimeUnit.SECONDS);
            assertThat(thrown).as("동시 경합이 500/예외로 새지 않아야 한다").isNull();

            // 한 행으로 수렴(패자는 새 행을 만들지 않고 승자 행을 교체) + 최종 payload 는 패자 것.
            assertThat(ledgerEntryRepository.count()).isEqualTo(1);
            LedgerEntry converged = ledgerEntryRepository
                    .findByMemberIdAndClientEntryId(MEMBER_ID, sharedClientEntryId)
                    .orElseThrow();
            assertThat(converged.getId()).isEqualTo(winningEntryId);
            assertThat(converged.getOriginalAmount()).isEqualByComparingTo(new BigDecimal("10.00"));
            assertThat(converged.getMemo()).isEqualTo("경합 패자");
            assertThat(converged.getClientEntryId()).isEqualTo(sharedClientEntryId);
            assertThat(converged.getClientPayloadHash()).isNull();
        } finally {
            winningCommitted.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("create()의 REQUIRES_NEW는 외부 트랜잭션 롤백과 독립적으로 커밋된다(별도 트랜잭션 보증)")
    void create_commits_in_separate_transaction_independent_of_outer_rollback() {
        UUID clientEntryId = UUID.fromString("10000000-0000-0000-0000-000000000105");
        // create() 가 REQUIRES_NEW 별도 트랜잭션이면 외부 롤백과 무관하게 커밋되어 행이 남는다.
        // (Spring 6.0+ 는 CGLIB 로 package-private 메서드의 @Transactional 도 적용 — 이 테스트가 그 적용을 보증한다.)
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            ledgerSyncInsertService.create(
                    MEMBER_ID, request(clientEntryId, new BigDecimal("1000.00"), CurrencyCode.KRW, TODAY, "inner"));
            status.setRollbackOnly();
        });
        assertThat(ledgerEntryRepository.findByMemberIdAndClientEntryId(MEMBER_ID, clientEntryId))
                .isPresent();
    }

    private SyncLedgerEntryRequest request(
            UUID clientEntryId, BigDecimal amount, CurrencyCode currencyCode, LocalDate transactionDate, String memo) {
        return new SyncLedgerEntryRequest(clientEntryId, amount, currencyCode, 1L, 3L, transactionDate, memo);
    }

    @TestConfiguration
    static class ClockConfig {

        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-04-05T15:00:00Z"), ZoneId.of("Asia/Seoul"));
        }
    }
}

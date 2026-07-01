package com.self.multi_currency_household_ledger.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;

import com.self.multi_currency_household_ledger.common.exception.BusinessException;
import com.self.multi_currency_household_ledger.exchange.domain.CurrencyCode;
import com.self.multi_currency_household_ledger.exchange.domain.ExchangeRate;
import com.self.multi_currency_household_ledger.exchange.service.ExchangeRateService;
import com.self.multi_currency_household_ledger.ledger.TestJpaConfig;
import com.self.multi_currency_household_ledger.ledger.TestLedgerApplication;
import com.self.multi_currency_household_ledger.ledger.domain.LedgerEntry;
import com.self.multi_currency_household_ledger.ledger.domain.LedgerEntryRepository;
import com.self.multi_currency_household_ledger.ledger.dto.ImportLedgerEntriesRequest;
import com.self.multi_currency_household_ledger.ledger.dto.ImportLedgerEntriesResponse;
import com.self.multi_currency_household_ledger.ledger.exception.LedgerErrorCode;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@Import({
    TestLedgerApplication.class,
    TestJpaConfig.class,
    LedgerService.class,
    LedgerSyncInsertService.class,
    LedgerImportServiceIntegrationTest.ClockConfig.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class LedgerImportServiceIntegrationTest {

    private static final UUID MEMBER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OTHER_MEMBER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final LocalDate TODAY = LocalDate.of(2026, 4, 6);

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @MockitoBean
    private ExchangeRateService exchangeRateService;

    @BeforeEach
    void setUp() {
        ledgerEntryRepository.deleteAll();
    }

    @Test
    @DisplayName("신규 배치 import는 서버 환율로 재해석하고 clientEntryId별 서버 거래를 반환한다")
    void import_entries_reinterprets_foreign_currency_with_server_rate() {
        UUID usdClientEntryId = UUID.fromString("10000000-0000-0000-0000-000000000001");
        UUID krwClientEntryId = UUID.fromString("10000000-0000-0000-0000-000000000002");
        ExchangeRate serverRate = ExchangeRate.of(CurrencyCode.USD, new BigDecimal("1300.000000"), TODAY.minusDays(1));
        given(exchangeRateService.getRateOnOrBeforeOrOldest(CurrencyCode.USD, TODAY))
                .willReturn(serverRate);
        ImportLedgerEntriesRequest request = new ImportLedgerEntriesRequest(List.of(
                item(usdClientEntryId, new BigDecimal("100.00"), CurrencyCode.USD, TODAY, "  점심  "),
                item(krwClientEntryId, new BigDecimal("5000.00"), CurrencyCode.KRW, TODAY, null)));

        ImportLedgerEntriesResponse response = ledgerService.importEntries(request, MEMBER_ID);

        assertThat(response.entries()).hasSize(2);
        assertThat(response.entries().get(0).clientEntryId()).isEqualTo(usdClientEntryId);
        assertThat(response.entries().get(0).ledgerEntry().id()).isNotNull();
        assertThat(response.entries().get(0).ledgerEntry().appliedRate())
                .isEqualByComparingTo(new BigDecimal("1300.000000"));
        assertThat(response.entries().get(0).ledgerEntry().krwAmount())
                .isEqualByComparingTo(new BigDecimal("130000.00"));
        assertThat(response.entries().get(0).ledgerEntry().rateBaseDate()).isEqualTo(TODAY.minusDays(1));
        assertThat(response.entries().get(0).ledgerEntry().memo()).isEqualTo("점심");
        assertThat(response.entries().get(1).ledgerEntry().appliedRate()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(response.entries().get(1).ledgerEntry().rateBaseDate()).isNull();

        LedgerEntry savedUsd = ledgerEntryRepository
                .findByMemberIdAndClientEntryId(MEMBER_ID, usdClientEntryId)
                .orElseThrow();
        assertThat(savedUsd.getClientPayloadHash()).hasSize(64);
        then(exchangeRateService).should().getRateOnOrBeforeOrOldest(CurrencyCode.USD, TODAY);
    }

    @Test
    @DisplayName("동일 payload 재import는 기존 거래를 반환하고 중복 행을 만들지 않는다")
    void import_entries_is_idempotent_for_same_payload() {
        UUID clientEntryId = UUID.fromString("10000000-0000-0000-0000-000000000011");
        ExchangeRate serverRate = ExchangeRate.of(CurrencyCode.USD, new BigDecimal("1300.000000"), TODAY);
        given(exchangeRateService.getRateOnOrBeforeOrOldest(CurrencyCode.USD, TODAY))
                .willReturn(serverRate);
        ImportLedgerEntriesRequest request = new ImportLedgerEntriesRequest(
                List.of(item(clientEntryId, new BigDecimal("100.0"), CurrencyCode.USD, TODAY, "점심")));

        ImportLedgerEntriesResponse first = ledgerService.importEntries(request, MEMBER_ID);
        ImportLedgerEntriesResponse second = ledgerService.importEntries(request, MEMBER_ID);

        assertThat(second.entries().getFirst().ledgerEntry().id())
                .isEqualTo(first.entries().getFirst().ledgerEntry().id());
        assertThat(ledgerEntryRepository.count()).isEqualTo(1);
        then(exchangeRateService).should(times(1)).getRateOnOrBeforeOrOldest(CurrencyCode.USD, TODAY);
    }

    @Test
    @DisplayName("동일 clientEntryId의 다른 payload는 409이고 배치 전체를 롤백한다")
    void import_entries_conflict_rolls_back_entire_batch() {
        UUID existingClientEntryId = UUID.fromString("10000000-0000-0000-0000-000000000021");
        UUID newClientEntryId = UUID.fromString("10000000-0000-0000-0000-000000000022");
        ledgerService.importEntries(
                new ImportLedgerEntriesRequest(
                        List.of(item(existingClientEntryId, new BigDecimal("1000.00"), CurrencyCode.KRW, TODAY, "기존"))),
                MEMBER_ID);
        ImportLedgerEntriesRequest conflictingBatch = new ImportLedgerEntriesRequest(List.of(
                item(newClientEntryId, new BigDecimal("2000.00"), CurrencyCode.KRW, TODAY, "새 거래"),
                item(existingClientEntryId, new BigDecimal("3000.00"), CurrencyCode.KRW, TODAY, "변경")));

        assertThatThrownBy(() -> ledgerService.importEntries(conflictingBatch, MEMBER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(LedgerErrorCode.LEDGER_IMPORT_CONFLICT.getCode());

        assertThat(ledgerEntryRepository.count()).isEqualTo(1);
        assertThat(ledgerEntryRepository.findByMemberIdAndClientEntryId(MEMBER_ID, newClientEntryId))
                .isEmpty();
        assertThat(ledgerEntryRepository
                        .findByMemberIdAndClientEntryId(MEMBER_ID, existingClientEntryId)
                        .orElseThrow()
                        .getOriginalAmount())
                .isEqualByComparingTo(new BigDecimal("1000.00"));
    }

    @Test
    @DisplayName("동시 import unique 경합은 409로 매핑하고 현재 배치를 롤백한다")
    void import_entries_maps_postgres_unique_race_to_conflict_and_rolls_back_batch() throws Exception {
        UUID racedClientEntryId = UUID.fromString("10000000-0000-0000-0000-000000000023");
        UUID newClientEntryId = UUID.fromString("10000000-0000-0000-0000-000000000024");
        LocalDate losingDate = TODAY;
        LocalDate winningDate = TODAY.minusDays(1);
        ExchangeRate losingRate = ExchangeRate.of(CurrencyCode.USD, new BigDecimal("1300.000000"), losingDate);
        ExchangeRate winningRate = ExchangeRate.of(CurrencyCode.USD, new BigDecimal("1200.000000"), winningDate);
        CountDownLatch losingReachedRateLookup = new CountDownLatch(1);
        CountDownLatch winningCommitted = new CountDownLatch(1);

        given(exchangeRateService.getRateOnOrBeforeOrOldest(CurrencyCode.USD, losingDate))
                .willAnswer(invocation -> {
                    losingReachedRateLookup.countDown();
                    assertThat(winningCommitted.await(5, TimeUnit.SECONDS)).isTrue();
                    return losingRate;
                });
        given(exchangeRateService.getRateOnOrBeforeOrOldest(CurrencyCode.USD, winningDate))
                .willReturn(winningRate);

        ImportLedgerEntriesRequest losingBatch = new ImportLedgerEntriesRequest(List.of(
                item(newClientEntryId, new BigDecimal("2000.00"), CurrencyCode.KRW, TODAY, "롤백 대상"),
                item(racedClientEntryId, new BigDecimal("10.00"), CurrencyCode.USD, losingDate, "경합 패자")));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            CompletableFuture<Throwable> losingResult = CompletableFuture.supplyAsync(
                    () -> {
                        try {
                            ledgerService.importEntries(losingBatch, MEMBER_ID);
                            return null;
                        } catch (Throwable throwable) {
                            return throwable;
                        }
                    },
                    executor);
            assertThat(losingReachedRateLookup.await(5, TimeUnit.SECONDS)).isTrue();

            ImportLedgerEntriesResponse winningResponse = ledgerService.importEntries(
                    new ImportLedgerEntriesRequest(List.of(
                            item(racedClientEntryId, new BigDecimal("20.00"), CurrencyCode.USD, winningDate, "경합 승자"))),
                    MEMBER_ID);
            Long winningEntryId =
                    winningResponse.entries().getFirst().ledgerEntry().id();
            winningCommitted.countDown();

            Throwable thrown = losingResult.get(5, TimeUnit.SECONDS);
            assertThat(thrown)
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getCode())
                    .isEqualTo(LedgerErrorCode.LEDGER_IMPORT_CONFLICT.getCode());

            assertThat(ledgerEntryRepository.count()).isEqualTo(1);
            assertThat(ledgerEntryRepository.findByMemberIdAndClientEntryId(MEMBER_ID, newClientEntryId))
                    .isEmpty();
            LedgerEntry winningEntry = ledgerEntryRepository
                    .findByMemberIdAndClientEntryId(MEMBER_ID, racedClientEntryId)
                    .orElseThrow();
            assertThat(winningEntry.getId()).isEqualTo(winningEntryId);
            assertThat(winningEntry.getOriginalAmount()).isEqualByComparingTo(new BigDecimal("20.00"));
            assertThat(winningEntry.getTransactionDate()).isEqualTo(winningDate);
            assertThat(winningEntry.getMemo()).isEqualTo("경합 승자");
        } finally {
            winningCommitted.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("배치 내 clientEntryId 중복은 409이고 저장하지 않는다")
    void import_entries_rejects_duplicate_client_entry_id_in_batch() {
        UUID clientEntryId = UUID.fromString("10000000-0000-0000-0000-000000000031");
        ImportLedgerEntriesRequest request = new ImportLedgerEntriesRequest(List.of(
                item(clientEntryId, new BigDecimal("1000.00"), CurrencyCode.KRW, TODAY, "첫 거래"),
                item(clientEntryId, new BigDecimal("1000.00"), CurrencyCode.KRW, TODAY, "첫 거래")));

        assertThatThrownBy(() -> ledgerService.importEntries(request, MEMBER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(LedgerErrorCode.LEDGER_IMPORT_CONFLICT.getCode());

        assertThat(ledgerEntryRepository.count()).isZero();
    }

    @Test
    @DisplayName("같은 clientEntryId도 member_id가 다르면 각자 별도 거래로 import된다")
    void import_entries_scopes_client_entry_id_by_member() {
        UUID sharedClientEntryId = UUID.fromString("10000000-0000-0000-0000-000000000041");

        ledgerService.importEntries(
                new ImportLedgerEntriesRequest(List.of(
                        item(sharedClientEntryId, new BigDecimal("1000.00"), CurrencyCode.KRW, TODAY, "memberA"))),
                MEMBER_ID);
        ledgerService.importEntries(
                new ImportLedgerEntriesRequest(List.of(
                        item(sharedClientEntryId, new BigDecimal("2000.00"), CurrencyCode.KRW, TODAY, "memberB"))),
                OTHER_MEMBER_ID);

        assertThat(ledgerEntryRepository.count()).isEqualTo(2);
        assertThat(ledgerEntryRepository
                        .findByMemberIdAndClientEntryId(MEMBER_ID, sharedClientEntryId)
                        .orElseThrow()
                        .getMemo())
                .isEqualTo("memberA");
        assertThat(ledgerEntryRepository
                        .findByMemberIdAndClientEntryId(OTHER_MEMBER_ID, sharedClientEntryId)
                        .orElseThrow()
                        .getMemo())
                .isEqualTo("memberB");
    }

    @Test
    @DisplayName("거래일 이전 환율 이력이 없는 외화 import는 가장 오래된 환율로 clamp되어 성공한다")
    void import_entries_uses_oldest_rate_clamp_for_old_foreign_entry() {
        UUID clientEntryId = UUID.fromString("10000000-0000-0000-0000-000000000051");
        LocalDate oldTransactionDate = LocalDate.of(2026, 1, 1);
        LocalDate oldestBaseDate = LocalDate.of(2026, 4, 1);
        given(exchangeRateService.getRateOnOrBeforeOrOldest(CurrencyCode.USD, oldTransactionDate))
                .willReturn(ExchangeRate.of(CurrencyCode.USD, new BigDecimal("1200.000000"), oldestBaseDate));

        ImportLedgerEntriesResponse response = ledgerService.importEntries(
                new ImportLedgerEntriesRequest(List.of(
                        item(clientEntryId, new BigDecimal("10.00"), CurrencyCode.USD, oldTransactionDate, "오래된 거래"))),
                MEMBER_ID);

        assertThat(response.entries().getFirst().ledgerEntry().appliedRate())
                .isEqualByComparingTo(new BigDecimal("1200.000000"));
        assertThat(response.entries().getFirst().ledgerEntry().rateBaseDate()).isEqualTo(oldestBaseDate);
        assertThat(response.entries().getFirst().ledgerEntry().krwAmount())
                .isEqualByComparingTo(new BigDecimal("12000.00"));
        then(exchangeRateService).should().getRateOnOrBeforeOrOldest(CurrencyCode.USD, oldTransactionDate);
    }

    private ImportLedgerEntriesRequest.ImportLedgerEntryItem item(
            UUID clientEntryId, BigDecimal amount, CurrencyCode currencyCode, LocalDate transactionDate, String memo) {
        return new ImportLedgerEntriesRequest.ImportLedgerEntryItem(
                clientEntryId, amount, currencyCode, 1L, 3L, transactionDate, memo);
    }

    @TestConfiguration
    static class ClockConfig {

        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-04-05T15:00:00Z"), ZoneId.of("Asia/Seoul"));
        }
    }
}

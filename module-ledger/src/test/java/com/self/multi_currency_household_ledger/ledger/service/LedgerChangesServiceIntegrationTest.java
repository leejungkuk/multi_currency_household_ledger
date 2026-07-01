package com.self.multi_currency_household_ledger.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;

import com.self.multi_currency_household_ledger.exchange.domain.CurrencyCode;
import com.self.multi_currency_household_ledger.exchange.domain.ExchangeRate;
import com.self.multi_currency_household_ledger.exchange.service.ExchangeRateService;
import com.self.multi_currency_household_ledger.ledger.TestJpaConfig;
import com.self.multi_currency_household_ledger.ledger.TestLedgerApplication;
import com.self.multi_currency_household_ledger.ledger.domain.LedgerEntryRepository;
import com.self.multi_currency_household_ledger.ledger.dto.LedgerChangesResponse;
import com.self.multi_currency_household_ledger.ledger.dto.SyncLedgerEntryRequest;
import com.self.multi_currency_household_ledger.ledger.dto.SyncLedgerEntryResponse;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
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
    LedgerRecalculationService.class,
    LedgerChangesServiceIntegrationTest.ClockConfig.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class LedgerChangesServiceIntegrationTest {

    private static final UUID MEMBER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final LocalDate TODAY = LocalDate.of(2026, 4, 6);

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private LedgerRecalculationService ledgerRecalculationService;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private ExchangeRateService exchangeRateService;

    @BeforeEach
    void setUp() {
        ledgerEntryRepository.deleteAll();
    }

    @Test
    @DisplayName("재계산으로 updated_at이 오른 외화 거래는 이전 커서 이후 changes pull에 확정 KRW 금액으로 반환된다")
    void changes_returns_recalculated_foreign_entry_after_previous_cursor() {
        UUID clientEntryId = UUID.fromString("10000000-0000-0000-0000-000000000401");
        LocalDateTime previousCursorUpdatedAt = LocalDateTime.of(2026, 4, 6, 9, 0);
        ExchangeRate staleRate = ExchangeRate.of(CurrencyCode.USD, new BigDecimal("1300.000000"), TODAY.minusDays(1));
        ExchangeRate confirmedRate = ExchangeRate.of(CurrencyCode.USD, new BigDecimal("1320.000000"), TODAY);
        given(exchangeRateService.getRateOnOrBefore(CurrencyCode.USD, TODAY))
                .willReturn(staleRate)
                .willReturn(confirmedRate);
        SyncLedgerEntryResponse synced = ledgerService.sync(
                request(clientEntryId, new BigDecimal("100.00"), CurrencyCode.USD, TODAY, "확정 전 외화"), MEMBER_ID);
        setUpdatedAt(synced.ledgerEntry().id(), previousCursorUpdatedAt);

        int recalculated = ledgerRecalculationService.recalculateRecentForeignEntries();
        LedgerChangesResponse response = ledgerService.getChanges(
                MEMBER_ID, previousCursorUpdatedAt, synced.ledgerEntry().id(), 500);

        assertThat(recalculated).isEqualTo(1);
        assertThat(response.hasMore()).isFalse();
        assertThat(response.entries()).singleElement().satisfies(entry -> {
            assertThat(entry.id()).isEqualTo(synced.ledgerEntry().id());
            assertThat(entry.clientEntryId()).isEqualTo(clientEntryId);
            assertThat(entry.appliedRate()).isEqualByComparingTo(new BigDecimal("1320.000000"));
            assertThat(entry.krwAmount()).isEqualByComparingTo(new BigDecimal("132000.00"));
            assertThat(entry.rateBaseDate()).isEqualTo(TODAY);
            assertThat(entry.updatedAt()).isAfter(previousCursorUpdatedAt);
        });
        assertThat(response.nextCursor()).isNotNull();
        assertThat(response.nextCursor().id()).isEqualTo(synced.ledgerEntry().id());
        assertThat(response.nextCursor().updatedAt())
                .isEqualTo(response.entries().getFirst().updatedAt());
    }

    @Test
    @DisplayName("changes pull은 hard-delete된 clientEntryId를 반환하지 않고 살아있는 행만 반환한다")
    void changes_excludes_hard_deleted_synced_entry() {
        UUID aliveClientEntryId = UUID.fromString("10000000-0000-0000-0000-000000000402");
        UUID deletedClientEntryId = UUID.fromString("10000000-0000-0000-0000-000000000403");
        SyncLedgerEntryResponse alive = ledgerService.sync(
                request(aliveClientEntryId, new BigDecimal("1000.00"), CurrencyCode.KRW, TODAY, "살아있는 거래"), MEMBER_ID);
        ledgerService.sync(
                request(deletedClientEntryId, new BigDecimal("2000.00"), CurrencyCode.KRW, TODAY, "삭제된 거래"), MEMBER_ID);

        ledgerService.deleteSyncedEntry(deletedClientEntryId, MEMBER_ID);
        LedgerChangesResponse response = ledgerService.getChanges(MEMBER_ID, null, null, 500);

        assertThat(response.entries())
                .extracting(LedgerChangesResponse.ChangedLedgerEntry::clientEntryId)
                .containsExactly(aliveClientEntryId)
                .doesNotContain(deletedClientEntryId);
        assertThat(response.entries())
                .singleElement()
                .extracting(LedgerChangesResponse.ChangedLedgerEntry::id)
                .isEqualTo(alive.ledgerEntry().id());
    }

    private SyncLedgerEntryRequest request(
            UUID clientEntryId, BigDecimal amount, CurrencyCode currencyCode, LocalDate transactionDate, String memo) {
        return new SyncLedgerEntryRequest(clientEntryId, amount, currencyCode, 1L, 3L, transactionDate, memo);
    }

    private void setUpdatedAt(Long id, LocalDateTime updatedAt) {
        jdbcTemplate.update("update ledger_entry set updated_at = ? where id = ?", updatedAt, id);
    }

    @TestConfiguration
    static class ClockConfig {

        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-04-05T15:00:00Z"), ZoneId.of("Asia/Seoul"));
        }
    }
}

package com.self.multi_currency_household_ledger.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.self.multi_currency_household_ledger.exchange.domain.CurrencyCode;
import com.self.multi_currency_household_ledger.exchange.domain.ExchangeRate;
import com.self.multi_currency_household_ledger.exchange.service.ExchangeRateService;
import com.self.multi_currency_household_ledger.ledger.domain.Asset;
import com.self.multi_currency_household_ledger.ledger.domain.Category;
import com.self.multi_currency_household_ledger.ledger.domain.LedgerEntry;
import com.self.multi_currency_household_ledger.ledger.domain.LedgerEntryRepository;
import com.self.multi_currency_household_ledger.ledger.domain.TransactionType;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LedgerRecalculationServiceTest {

    private static final UUID MEMBER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final LocalDate TODAY = LocalDate.of(2026, 4, 6);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-04-05T15:00:00Z"), KST);
    private static final int WINDOW_DAYS = 7;

    @Mock
    private LedgerEntryRepository ledgerEntryRepository;

    @Mock
    private ExchangeRateService exchangeRateService;

    private LedgerRecalculationService service;
    private Category category;
    private Asset asset;

    @BeforeEach
    void setUp() {
        service = new LedgerRecalculationService(ledgerEntryRepository, exchangeRateService, FIXED_CLOCK, WINDOW_DAYS);
        category = new Category(TransactionType.EXPENSE, "FOOD", "식비", "icon", 1, Category.SYSTEM_OWNER_ID);
        asset = new Asset("CASH", "현금", "icon", 1, Asset.SYSTEM_OWNER_ID);
    }

    @Test
    @DisplayName("보정창 내 오래된 외화 거래를 적용 가능한 최신 tts로 재계산한다")
    void recalculates_stale_foreign_entries_inside_window() {
        LocalDate transactionDate = TODAY;
        LedgerEntry entry = foreignEntry(transactionDate, TODAY.minusDays(1), "1300.000000");
        ExchangeRate applicableRate = ExchangeRate.of(CurrencyCode.USD, new BigDecimal("1320.000000"), TODAY);
        given(ledgerEntryRepository.findForeignEntriesOnOrAfter(TODAY.minusDays(WINDOW_DAYS)))
                .willReturn(List.of(entry));
        given(exchangeRateService.getRateOnOrBefore(CurrencyCode.USD, transactionDate))
                .willReturn(applicableRate);

        int recalculated = service.recalculateRecentForeignEntries();

        assertThat(recalculated).isEqualTo(1);
        assertThat(entry.getAppliedRate()).isEqualByComparingTo(new BigDecimal("1320.000000"));
        assertThat(entry.getRateBaseDate()).isEqualTo(TODAY);
        assertThat(entry.getKrwAmount()).isEqualByComparingTo(new BigDecimal("132000.00"));
    }

    @Test
    @DisplayName("한 번 재계산된 거래는 두 번째 실행에서 no-op이 되어 멱등·수렴한다")
    void second_run_is_noop_after_rate_base_date_converges() {
        LedgerEntry entry = foreignEntry(TODAY, TODAY.minusDays(1), "1300.000000");
        ExchangeRate applicableRate = ExchangeRate.of(CurrencyCode.USD, new BigDecimal("1320.000000"), TODAY);
        given(ledgerEntryRepository.findForeignEntriesOnOrAfter(TODAY.minusDays(WINDOW_DAYS)))
                .willReturn(List.of(entry));
        given(exchangeRateService.getRateOnOrBefore(CurrencyCode.USD, TODAY)).willReturn(applicableRate);

        int first = service.recalculateRecentForeignEntries();
        int second = service.recalculateRecentForeignEntries();

        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
        assertThat(entry.getRateBaseDate()).isEqualTo(TODAY);
        assertThat(entry.getKrwAmount()).isEqualByComparingTo(new BigDecimal("132000.00"));
    }

    @Test
    @DisplayName("공휴일·주말 fallback 환율이 적용 가능한 최신 기준일이면 재계산하지 않는다")
    void holiday_fallback_entry_is_noop_when_previous_business_rate_is_applicable_latest() {
        LocalDate saturday = LocalDate.of(2026, 4, 4);
        LocalDate friday = LocalDate.of(2026, 4, 3);
        LedgerEntry entry = foreignEntry(saturday, friday, "1300.000000");
        ExchangeRate applicableRate = ExchangeRate.of(CurrencyCode.USD, new BigDecimal("1300.000000"), friday);
        given(ledgerEntryRepository.findForeignEntriesOnOrAfter(TODAY.minusDays(WINDOW_DAYS)))
                .willReturn(List.of(entry));
        given(exchangeRateService.getRateOnOrBefore(CurrencyCode.USD, saturday)).willReturn(applicableRate);

        int recalculated = service.recalculateRecentForeignEntries();

        assertThat(recalculated).isZero();
        assertThat(entry.getRateBaseDate()).isEqualTo(friday);
        assertThat(entry.getKrwAmount()).isEqualByComparingTo(new BigDecimal("130000.00"));
    }

    @Test
    @DisplayName("보정창 밖 거래는 조회 대상이 아니므로 불변이다")
    void entries_before_window_are_not_recalculated() {
        LocalDate cutoff = TODAY.minusDays(WINDOW_DAYS);
        given(ledgerEntryRepository.findForeignEntriesOnOrAfter(cutoff)).willReturn(List.of());

        int recalculated = service.recalculateRecentForeignEntries();

        assertThat(recalculated).isZero();
        verify(exchangeRateService, never())
                .getRateOnOrBefore(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("KRW 거래가 잘못 전달되어도 도메인 재계산은 불변이다")
    void krw_entries_remain_unchanged_even_if_returned_by_repository() {
        LedgerEntry entry = LedgerEntry.of(
                MEMBER_ID,
                category,
                asset,
                new BigDecimal("5000.00"),
                CurrencyCode.KRW,
                TODAY,
                "원화",
                null,
                FIXED_CLOCK);
        given(ledgerEntryRepository.findForeignEntriesOnOrAfter(TODAY.minusDays(WINDOW_DAYS)))
                .willReturn(List.of(entry));

        int recalculated = service.recalculateRecentForeignEntries();

        assertThat(recalculated).isZero();
        assertThat(entry.getAppliedRate()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(entry.getRateBaseDate()).isNull();
        assertThat(entry.getKrwAmount()).isEqualByComparingTo(new BigDecimal("5000.00"));
        verify(exchangeRateService, never())
                .getRateOnOrBefore(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    private LedgerEntry foreignEntry(LocalDate transactionDate, LocalDate rateBaseDate, String tts) {
        ExchangeRate rate = ExchangeRate.of(CurrencyCode.USD, new BigDecimal(tts), rateBaseDate);
        return LedgerEntry.of(
                MEMBER_ID,
                category,
                asset,
                new BigDecimal("100.00"),
                CurrencyCode.USD,
                transactionDate,
                "외화",
                rate,
                FIXED_CLOCK);
    }
}

package com.self.multi_currency_household_ledger.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.self.multi_currency_household_ledger.exchange.domain.CurrencyCode;
import com.self.multi_currency_household_ledger.exchange.domain.ExchangeRate;
import com.self.multi_currency_household_ledger.exchange.service.ExchangeRateService;
import com.self.multi_currency_household_ledger.ledger.domain.Asset;
import com.self.multi_currency_household_ledger.ledger.domain.Category;
import com.self.multi_currency_household_ledger.ledger.domain.LedgerEntry;
import com.self.multi_currency_household_ledger.ledger.domain.LedgerEntryRepository;
import com.self.multi_currency_household_ledger.ledger.domain.TransactionType;
import com.self.multi_currency_household_ledger.ledger.service.LedgerRecalculationChunkProcessor.ChunkResult;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class LedgerRecalculationChunkProcessorTest {

    private static final UUID MEMBER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final LocalDate TODAY = LocalDate.of(2026, 4, 6);
    private static final LocalDate WINDOW_START = TODAY.minusDays(7);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-04-05T15:00:00Z"), KST);
    private static final int CHUNK_SIZE = 3;

    @Mock
    private LedgerEntryRepository ledgerEntryRepository;

    @Mock
    private ExchangeRateService exchangeRateService;

    private LedgerRecalculationChunkProcessor processor;
    private Category category;
    private Asset asset;

    @BeforeEach
    void setUp() {
        processor = new LedgerRecalculationChunkProcessor(ledgerEntryRepository, exchangeRateService);
        category = new Category(TransactionType.EXPENSE, "FOOD_DINING", "식비", "Food & Dining", "🍽️", 1);
        asset = new Asset("CASH", "현금", "Cash", 3);
    }

    @Test
    @DisplayName("보정창 내 오래된 외화 거래를 적용 가능한 최신 tts로 재계산한다")
    void recalculates_stale_foreign_entries_inside_window() {
        LedgerEntry entry = foreignEntry(TODAY, TODAY.minusDays(1), "1300.000000");
        givenChunk(entry);
        given(exchangeRateService.getRateOnOrBefore(CurrencyCode.USD, TODAY)).willReturn(rate("1320.000000", TODAY));

        ChunkResult result = recalculateFirstChunk();

        assertThat(result.recalculated()).isEqualTo(1);
        assertThat(entry.getAppliedRate()).isEqualByComparingTo(new BigDecimal("1320.000000"));
        assertThat(entry.getRateBaseDate()).isEqualTo(TODAY);
        assertThat(entry.getKrwAmount()).isEqualByComparingTo(new BigDecimal("132000.00"));
    }

    @Test
    @DisplayName("한 번 재계산된 거래는 두 번째 실행에서 no-op이 되어 멱등·수렴한다")
    void second_run_is_noop_after_rate_base_date_converges() {
        LedgerEntry entry = foreignEntry(TODAY, TODAY.minusDays(1), "1300.000000");
        givenChunk(entry);
        given(exchangeRateService.getRateOnOrBefore(CurrencyCode.USD, TODAY)).willReturn(rate("1320.000000", TODAY));

        int first = recalculateFirstChunk().recalculated();
        int second = recalculateFirstChunk().recalculated();

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
        givenChunk(entry);
        given(exchangeRateService.getRateOnOrBefore(CurrencyCode.USD, saturday))
                .willReturn(rate("1300.000000", friday));

        ChunkResult result = recalculateFirstChunk();

        assertThat(result.recalculated()).isZero();
        assertThat(entry.getRateBaseDate()).isEqualTo(friday);
        assertThat(entry.getKrwAmount()).isEqualByComparingTo(new BigDecimal("130000.00"));
    }

    @Test
    @DisplayName("대상 거래가 없으면 환율을 조회하지 않고 다음 청크도 열지 않는다")
    void empty_chunk_does_not_touch_exchange_rates_and_has_no_next_cursor() {
        givenChunk();

        ChunkResult result = recalculateFirstChunk();

        assertThat(result.recalculated()).isZero();
        assertThat(result.hasMore()).isFalse();
        verify(exchangeRateService, never()).getRateOnOrBefore(any(), any());
    }

    @Test
    @DisplayName("KRW 거래가 잘못 전달되어도 환율 조회 없이 불변이다")
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
        givenChunk(entry);

        ChunkResult result = recalculateFirstChunk();

        assertThat(result.recalculated()).isZero();
        assertThat(entry.getAppliedRate()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(entry.getRateBaseDate()).isNull();
        assertThat(entry.getKrwAmount()).isEqualByComparingTo(new BigDecimal("5000.00"));
        verify(exchangeRateService, never()).getRateOnOrBefore(any(), any());
    }

    @Test
    @DisplayName("환율은 거래 건수가 아니라 (통화, 거래일) 조합 수만큼만 조회한다")
    void looks_up_exchange_rate_once_per_currency_and_date_group() {
        LedgerEntry usdToday1 = foreignEntry(TODAY, TODAY.minusDays(1), "1300.000000");
        LedgerEntry usdToday2 = foreignEntry(TODAY, TODAY.minusDays(1), "1300.000000");
        LedgerEntry usdYesterday = foreignEntry(TODAY.minusDays(1), TODAY.minusDays(2), "1290.000000");
        givenChunk(usdToday1, usdToday2, usdYesterday);
        given(exchangeRateService.getRateOnOrBefore(CurrencyCode.USD, TODAY)).willReturn(rate("1320.000000", TODAY));
        given(exchangeRateService.getRateOnOrBefore(CurrencyCode.USD, TODAY.minusDays(1)))
                .willReturn(rate("1310.000000", TODAY.minusDays(1)));

        ChunkResult result = recalculateFirstChunk();

        assertThat(result.recalculated()).isEqualTo(3);
        verify(exchangeRateService, times(1)).getRateOnOrBefore(CurrencyCode.USD, TODAY);
        verify(exchangeRateService, times(1)).getRateOnOrBefore(CurrencyCode.USD, TODAY.minusDays(1));
    }

    @Test
    @DisplayName("청크가 가득 차면 마지막 행의 (거래일, id)를 다음 커서로 돌려준다")
    void full_chunk_returns_last_row_as_next_cursor() {
        LedgerEntry first = foreignEntry(TODAY.minusDays(1), TODAY.minusDays(2), "1290.000000");
        LedgerEntry second = foreignEntry(TODAY, TODAY.minusDays(1), "1300.000000");
        LedgerEntry last = withId(foreignEntry(TODAY, TODAY.minusDays(1), "1300.000000"), 77L);
        givenChunk(first, second, last);
        given(exchangeRateService.getRateOnOrBefore(CurrencyCode.USD, TODAY)).willReturn(rate("1320.000000", TODAY));
        given(exchangeRateService.getRateOnOrBefore(CurrencyCode.USD, TODAY.minusDays(1)))
                .willReturn(rate("1310.000000", TODAY.minusDays(1)));

        ChunkResult result = recalculateFirstChunk();

        assertThat(result.hasMore()).isTrue();
        assertThat(result.nextCursorDate()).isEqualTo(TODAY);
        assertThat(result.nextCursorId()).isEqualTo(77L);
    }

    private ChunkResult recalculateFirstChunk() {
        return processor.recalculateChunk(WINDOW_START, 0L, CHUNK_SIZE);
    }

    private void givenChunk(LedgerEntry... entries) {
        given(ledgerEntryRepository.findStaleForeignEntriesAfterCursor(WINDOW_START, 0L, PageRequest.of(0, CHUNK_SIZE)))
                .willReturn(List.of(entries));
    }

    private ExchangeRate rate(String tts, LocalDate baseDate) {
        return ExchangeRate.of(CurrencyCode.USD, new BigDecimal(tts), baseDate);
    }

    private LedgerEntry foreignEntry(LocalDate transactionDate, LocalDate rateBaseDate, String tts) {
        return LedgerEntry.of(
                MEMBER_ID,
                category,
                asset,
                new BigDecimal("100.00"),
                CurrencyCode.USD,
                transactionDate,
                "외화",
                ExchangeRate.of(CurrencyCode.USD, new BigDecimal(tts), rateBaseDate),
                FIXED_CLOCK);
    }

    private LedgerEntry withId(LedgerEntry entry, Long id) {
        ReflectionTestUtils.setField(entry, "id", id);
        return entry;
    }
}

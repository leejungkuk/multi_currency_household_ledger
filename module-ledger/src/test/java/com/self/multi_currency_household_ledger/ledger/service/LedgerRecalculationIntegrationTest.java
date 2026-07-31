package com.self.multi_currency_household_ledger.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;

import com.self.multi_currency_household_ledger.common.exception.BusinessException;
import com.self.multi_currency_household_ledger.exchange.domain.CurrencyCode;
import com.self.multi_currency_household_ledger.exchange.domain.ExchangeRate;
import com.self.multi_currency_household_ledger.exchange.exception.ExchangeErrorCode;
import com.self.multi_currency_household_ledger.exchange.service.ExchangeRateService;
import com.self.multi_currency_household_ledger.ledger.TestJpaConfig;
import com.self.multi_currency_household_ledger.ledger.TestLedgerApplication;
import com.self.multi_currency_household_ledger.ledger.domain.LedgerEntry;
import com.self.multi_currency_household_ledger.ledger.domain.LedgerEntryRepository;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 재계산 배치의 대상 선정은 SQL 술어(적용 가능한 최신 tts 보다 오래된 환율을 쓰는 행)라 실DB에서만 검증된다.
 * exchange_rate 행과 {@link ExchangeRateService} 스텁은 같은 값으로 맞춰 둔다 — 술어와 서비스가 같은 환율을
 * 가리켜야 재계산이 수렴한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@Import({
    TestLedgerApplication.class,
    TestJpaConfig.class,
    LedgerRecalculationChunkProcessor.class,
    LedgerRecalculationIntegrationTest.ClockConfig.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class LedgerRecalculationIntegrationTest {

    private static final UUID MEMBER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final LocalDate TODAY = LocalDate.of(2026, 4, 6);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-04-05T15:00:00Z"), KST);
    private static final int WINDOW_DAYS = 7;
    private static final int PROXIED_CHUNK_SIZE = 2;

    @Autowired
    private LedgerRecalculationChunkProcessor chunkProcessor;

    /** 트랜잭션 경계를 보는 테스트만 이 빈을 쓴다 — {@code new} 로 만들면 프록시가 없어 {@code @Transactional} 이 통째로 무력해진다. */
    @Autowired
    private LedgerRecalculationService proxiedService;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private ExchangeRateService exchangeRateService;

    @BeforeEach
    void setUp() {
        ledgerEntryRepository.deleteAll();
        jdbcTemplate.update("delete from exchange_rate");
    }

    @Test
    @DisplayName("청크 크기보다 대상이 많아도 경계에서 빠짐·중복 없이 전부 재계산한다")
    void recalculates_every_stale_entry_across_chunks() {
        insertRate(CurrencyCode.USD, "1200.000000", TODAY.minusDays(2));
        insertRate(CurrencyCode.USD, "1300.000000", TODAY);
        stubRate(TODAY.minusDays(2), "1200.000000", TODAY.minusDays(2));
        stubRate(TODAY, "1300.000000", TODAY);
        // 같은 거래일에 3건 → 청크 경계가 날짜 그룹 안에 떨어진다.
        List<Long> ids = List.of(
                insertStaleEntry(TODAY.minusDays(2), TODAY.minusDays(3)),
                insertStaleEntry(TODAY.minusDays(2), TODAY.minusDays(3)),
                insertStaleEntry(TODAY.minusDays(2), TODAY.minusDays(3)),
                insertStaleEntry(TODAY, TODAY.minusDays(1)),
                insertStaleEntry(TODAY, TODAY.minusDays(1)));

        int recalculated = service(2, 100).recalculateRecentForeignEntries();

        assertThat(recalculated).isEqualTo(5);
        assertThat(ids.subList(0, 3)).allSatisfy(id -> assertRecalculated(id, "1200.000000", TODAY.minusDays(2)));
        assertThat(ids.subList(3, 5)).allSatisfy(id -> assertRecalculated(id, "1300.000000", TODAY));
    }

    @Test
    @DisplayName("주기 상한을 넘긴 나머지는 영구 동결되지 않고 다음 주기에 이어서 재계산된다")
    void entries_beyond_max_per_run_are_recalculated_by_the_next_run() {
        insertRate(CurrencyCode.USD, "1300.000000", TODAY);
        stubRate(TODAY, "1300.000000", TODAY);
        List<Long> ids = List.of(
                insertStaleEntry(TODAY, TODAY.minusDays(1)),
                insertStaleEntry(TODAY, TODAY.minusDays(1)),
                insertStaleEntry(TODAY, TODAY.minusDays(1)),
                insertStaleEntry(TODAY, TODAY.minusDays(1)),
                insertStaleEntry(TODAY, TODAY.minusDays(1)));
        LedgerRecalculationService service = service(2, 3);

        int firstRun = service.recalculateRecentForeignEntries();

        assertThat(firstRun).isEqualTo(3);
        assertThat(staleEntryCount()).isEqualTo(2);

        int secondRun = service.recalculateRecentForeignEntries();

        assertThat(secondRun).isEqualTo(2);
        assertThat(staleEntryCount()).isZero();
        assertThat(ids).allSatisfy(id -> assertRecalculated(id, "1300.000000", TODAY));
    }

    @Test
    @DisplayName("뒤 청크가 실패해도 앞 청크가 재계산한 행은 커밋된 채 남는다")
    void earlier_chunk_stays_committed_when_a_later_chunk_fails() {
        insertRate(CurrencyCode.USD, "1200.000000", TODAY.minusDays(2));
        insertRate(CurrencyCode.USD, "1300.000000", TODAY);
        stubRate(TODAY.minusDays(2), "1200.000000", TODAY.minusDays(2));
        given(exchangeRateService.getRateOnOrBefore(CurrencyCode.USD, TODAY))
                .willThrow(new BusinessException(ExchangeErrorCode.EXCHANGE_RATE_NOT_FOUND));
        // 앞선 거래일 PROXIED_CHUNK_SIZE 건이 첫 청크를 채우고, 두 번째 청크가 환율 조회에서 터진다.
        List<Long> firstChunk = List.of(
                insertStaleEntry(TODAY.minusDays(2), TODAY.minusDays(3)),
                insertStaleEntry(TODAY.minusDays(2), TODAY.minusDays(3)));
        long failedChunk = insertStaleEntry(TODAY, TODAY.minusDays(1));

        assertThatThrownBy(() -> proxiedService.recalculateRecentForeignEntries())
                .isInstanceOf(BusinessException.class);

        assertThat(firstChunk).allSatisfy(id -> assertRecalculated(id, "1200.000000", TODAY.minusDays(2)));
        assertThat(rateBaseDateOf(failedChunk)).isEqualTo(TODAY.minusDays(1));
    }

    @Test
    @DisplayName("이미 적용 가능한 최신 환율을 쓰는 거래·KRW 거래는 재계산 대상에 들어오지 않는다")
    void up_to_date_and_krw_entries_are_never_selected() {
        insertRate(CurrencyCode.USD, "1250.000000", TODAY.minusDays(3));
        insertRate(CurrencyCode.USD, "1300.000000", TODAY);
        long upToDate = insertStaleEntry(TODAY, TODAY);
        // 주말·공휴일 fallback: 거래일에 적용 가능한 최신 환율이 그보다 앞선 기준일이면 이미 최신이다.
        long fallback = insertStaleEntry(TODAY.minusDays(2), TODAY.minusDays(3));
        long krw = insertEntry(CurrencyCode.KRW, TODAY, null, "1.000000", "5000.00");

        int recalculated = service(10, 100).recalculateRecentForeignEntries();

        assertThat(recalculated).isZero();
        assertThat(rateBaseDateOf(upToDate)).isEqualTo(TODAY);
        assertThat(rateBaseDateOf(fallback)).isEqualTo(TODAY.minusDays(3));
        assertThat(rateBaseDateOf(krw)).isNull();
        assertThat(appliedRateOf(krw)).isEqualByComparingTo(BigDecimal.ONE);
    }

    @Test
    @DisplayName("rate_base_date가 비어 있는 외화 거래도 배치가 최신 tts로 복구한다")
    void entries_without_rate_base_date_are_restored_to_the_latest_rate() {
        insertRate(CurrencyCode.USD, "1300.000000", TODAY);
        stubRate(TODAY, "1300.000000", TODAY);
        long id = insertEntry(CurrencyCode.USD, TODAY, null, "1000.000000", "100000.00");

        int recalculated = service(2, 100).recalculateRecentForeignEntries();

        assertThat(recalculated).isEqualTo(1);
        assertRecalculated(id, "1300.000000", TODAY);
    }

    @Test
    @DisplayName("재계산은 멱등하다 — 연속 두 번째 실행의 처리 건수는 0이다")
    void second_consecutive_run_recalculates_nothing() {
        insertRate(CurrencyCode.USD, "1300.000000", TODAY);
        stubRate(TODAY, "1300.000000", TODAY);
        insertStaleEntry(TODAY, TODAY.minusDays(1));
        LedgerRecalculationService service = service(2, 100);

        assertThat(service.recalculateRecentForeignEntries()).isEqualTo(1);
        assertThat(service.recalculateRecentForeignEntries()).isZero();
    }

    @Test
    @DisplayName("keyset 커서는 반열림이라 커서 행 자체는 다음 페이지에 다시 나오지 않는다")
    void cursor_row_is_excluded_from_the_next_page() {
        insertRate(CurrencyCode.USD, "1300.000000", TODAY);
        List<Long> ids = List.of(
                insertStaleEntry(TODAY, TODAY.minusDays(1)),
                insertStaleEntry(TODAY, TODAY.minusDays(1)),
                insertStaleEntry(TODAY, TODAY.minusDays(1)));

        List<LedgerEntry> firstPage = ledgerEntryRepository.findStaleForeignEntriesAfterCursor(
                TODAY.minusDays(WINDOW_DAYS), 0L, PageRequest.of(0, 2));
        List<LedgerEntry> nextPage = ledgerEntryRepository.findStaleForeignEntriesAfterCursor(
                firstPage.getLast().getTransactionDate(), firstPage.getLast().getId(), PageRequest.of(0, 2));

        assertThat(firstPage).extracting(LedgerEntry::getId).containsExactly(ids.get(0), ids.get(1));
        assertThat(nextPage).extracting(LedgerEntry::getId).containsExactly(ids.get(2));
    }

    private LedgerRecalculationService service(int chunkSize, int maxEntriesPerRun) {
        return new LedgerRecalculationService(chunkProcessor, FIXED_CLOCK, WINDOW_DAYS, chunkSize, maxEntriesPerRun);
    }

    private void stubRate(LocalDate transactionDate, String tts, LocalDate baseDate) {
        given(exchangeRateService.getRateOnOrBefore(CurrencyCode.USD, transactionDate))
                .willReturn(ExchangeRate.of(CurrencyCode.USD, new BigDecimal(tts), baseDate));
    }

    private void insertRate(CurrencyCode currencyCode, String tts, LocalDate baseDate) {
        jdbcTemplate.update(
                "insert into exchange_rate (currency_code, tts, base_date) values (?, ?, ?)",
                currencyCode.name(),
                new BigDecimal(tts),
                baseDate);
    }

    private long insertStaleEntry(LocalDate transactionDate, LocalDate rateBaseDate) {
        return insertEntry(CurrencyCode.USD, transactionDate, rateBaseDate, "1000.000000", "100000.00");
    }

    private long insertEntry(
            CurrencyCode currencyCode,
            LocalDate transactionDate,
            LocalDate rateBaseDate,
            String appliedRate,
            String krwAmount) {
        return jdbcTemplate.queryForObject(
                """
                insert into ledger_entry (member_id, transaction_type, category_id, asset_id, original_amount,
                    currency_code, applied_rate, rate_base_date, krw_amount, transaction_date, memo,
                    created_at, updated_at)
                values (?, 'EXPENSE', 1, 3, 100.00, ?, ?, ?, ?, ?, '재계산 대상', now(), now())
                returning id
                """,
                Long.class,
                MEMBER_ID,
                currencyCode.name(),
                new BigDecimal(appliedRate),
                rateBaseDate,
                new BigDecimal(krwAmount),
                transactionDate);
    }

    private void assertRecalculated(long id, String expectedRate, LocalDate expectedBaseDate) {
        LedgerEntry entry = ledgerEntryRepository.findById(id).orElseThrow();
        assertThat(entry.getAppliedRate()).isEqualByComparingTo(new BigDecimal(expectedRate));
        assertThat(entry.getRateBaseDate()).isEqualTo(expectedBaseDate);
        assertThat(entry.getKrwAmount())
                .isEqualByComparingTo(new BigDecimal(expectedRate).multiply(new BigDecimal("100.00")));
    }

    private int staleEntryCount() {
        return ledgerEntryRepository
                .findStaleForeignEntriesAfterCursor(TODAY.minusDays(WINDOW_DAYS), 0L, PageRequest.of(0, 100))
                .size();
    }

    private LocalDate rateBaseDateOf(long id) {
        return ledgerEntryRepository.findById(id).orElseThrow().getRateBaseDate();
    }

    private BigDecimal appliedRateOf(long id) {
        return ledgerEntryRepository.findById(id).orElseThrow().getAppliedRate();
    }

    @TestConfiguration
    static class ClockConfig {

        @Bean
        Clock clock() {
            return FIXED_CLOCK;
        }

        @Bean
        LedgerRecalculationService proxiedService(LedgerRecalculationChunkProcessor chunkProcessor, Clock clock) {
            return new LedgerRecalculationService(chunkProcessor, clock, WINDOW_DAYS, PROXIED_CHUNK_SIZE, 100);
        }
    }
}

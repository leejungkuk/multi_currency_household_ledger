package com.self.multi_currency_household_ledger.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;

import com.self.multi_currency_household_ledger.exchange.domain.CurrencyCode;
import com.self.multi_currency_household_ledger.exchange.domain.ExchangeRate;
import com.self.multi_currency_household_ledger.ledger.TestJpaConfig;
import com.self.multi_currency_household_ledger.ledger.TestLedgerApplication;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@Import({TestLedgerApplication.class, TestJpaConfig.class})
class LedgerEntryRepositoryTest {

    private static final UUID MEMBER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final LocalDate TODAY = LocalDate.of(2026, 4, 6);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-04-05T15:00:00Z"), KST);

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private AssetRepository assetRepository;

    private Category category;
    private Category incomeCategory;
    private Asset asset;

    @BeforeEach
    void setUp() {
        category = categoryRepository.save(
                new Category(TransactionType.EXPENSE, "TEST_FOOD", "테스트 식비", "Test Food", "icon-food", 100));
        incomeCategory = categoryRepository.save(
                new Category(TransactionType.INCOME, "TEST_SALARY", "테스트 급여", "Test Salary", "icon-salary", 100));
        asset = assetRepository.save(new Asset("TEST_CASH", "테스트 현금", "Test Cash", 100));
    }

    // 가계부 내역을 정상적으로 저장할 수 있는지 확인한다.
    @Test
    @DisplayName("가계부 내역을 저장하고 조회할 수 있다")
    void save_and_find_ledger_entry() {
        LedgerEntry entry = LedgerEntry.of(
                MEMBER_ID, category, asset, BigDecimal.valueOf(5000), CurrencyCode.KRW, TODAY, "커피", null, FIXED_CLOCK);

        LedgerEntry saved = ledgerEntryRepository.save(entry);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getMemberId()).isEqualTo(MEMBER_ID);
        assertThat(saved.getOriginalAmount()).isEqualByComparingTo(BigDecimal.valueOf(5000));
        assertThat(saved.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("member_id는 Postgres uuid 타입으로 저장된다")
    void save_ledger_entry_with_uuid_member_id() {
        LedgerEntry entry = LedgerEntry.of(
                MEMBER_ID, category, asset, BigDecimal.valueOf(5000), CurrencyCode.KRW, TODAY, "커피", null, FIXED_CLOCK);

        LedgerEntry saved = ledgerEntryRepository.saveAndFlush(entry);

        UUID storedMemberId = jdbcTemplate.queryForObject(
                "select member_id from ledger_entry where id = ?", UUID.class, saved.getId());
        String columnType = jdbcTemplate.queryForObject(
                """
                select udt_name
                from information_schema.columns
                where table_name = 'ledger_entry'
                  and column_name = 'member_id'
                """,
                String.class);
        assertThat(storedMemberId).isEqualTo(MEMBER_ID);
        assertThat(columnType).isEqualTo("uuid");
    }

    @Test
    @DisplayName("보정창 시작일 이후 외화 거래만 조회한다")
    void find_foreign_entries_on_or_after_correction_window() {
        LocalDate cutoff = TODAY.minusDays(7);
        ExchangeRate usdRate = ExchangeRate.of(CurrencyCode.USD, new BigDecimal("1300.000000"), cutoff);
        LedgerEntry staleForeign = LedgerEntry.of(
                MEMBER_ID,
                category,
                asset,
                new BigDecimal("100.00"),
                CurrencyCode.USD,
                cutoff,
                "보정창 내 외화",
                usdRate,
                FIXED_CLOCK);
        LedgerEntry krwEntry = LedgerEntry.of(
                MEMBER_ID,
                category,
                asset,
                new BigDecimal("5000.00"),
                CurrencyCode.KRW,
                TODAY,
                "원화",
                null,
                FIXED_CLOCK);
        LedgerEntry oldForeign = LedgerEntry.of(
                MEMBER_ID,
                category,
                asset,
                new BigDecimal("100.00"),
                CurrencyCode.USD,
                cutoff.minusDays(1),
                "보정창 밖 외화",
                usdRate,
                FIXED_CLOCK);

        ledgerEntryRepository.saveAll(List.of(staleForeign, krwEntry, oldForeign));
        ledgerEntryRepository.flush();

        List<LedgerEntry> entries = ledgerEntryRepository.findForeignEntriesOnOrAfter(cutoff);

        assertThat(entries).extracting(LedgerEntry::getMemo).containsExactly("보정창 내 외화");
    }

    @Test
    @DisplayName("월 합계는 member_id와 기간, 거래 유형으로 격리해 집계한다")
    void sum_krw_amount_by_member_period_and_transaction_type() {
        UUID otherMemberId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        LocalDate startDate = LocalDate.of(2026, 4, 1);
        LocalDate endDate = LocalDate.of(2026, 5, 1);

        ledgerEntryRepository.saveAll(List.of(
                krwEntry(MEMBER_ID, incomeCategory, startDate, "3000.00", "내 수입"),
                krwEntry(MEMBER_ID, category, startDate.plusDays(1), "1000.00", "내 지출"),
                krwEntry(otherMemberId, category, startDate.plusDays(2), "90000.00", "다른 회원 지출"),
                krwEntry(MEMBER_ID, category, startDate.minusDays(1), "500.00", "전월 지출")));
        ledgerEntryRepository.flush();

        BigDecimal income = ledgerEntryRepository.sumKrwAmountByMemberIdAndTransactionTypeAndTransactionDateRange(
                MEMBER_ID, TransactionType.INCOME, startDate, endDate);
        BigDecimal expense = ledgerEntryRepository.sumKrwAmountByMemberIdAndTransactionTypeAndTransactionDateRange(
                MEMBER_ID, TransactionType.EXPENSE, startDate, endDate);

        assertThat(income).isEqualByComparingTo(new BigDecimal("3000.00"));
        assertThat(expense).isEqualByComparingTo(new BigDecimal("1000.00"));
    }

    @Test
    @DisplayName("월 목록은 member_id로 격리하고 거래일 내림차순, id 내림차순, 하드 캡으로 조회한다")
    void find_monthly_entries_filters_member_sorts_by_date_and_id_desc_with_cap() {
        UUID otherMemberId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        LocalDate startDate = LocalDate.of(2026, 4, 1);
        LocalDate endDate = LocalDate.of(2026, 5, 1);

        ledgerEntryRepository.saveAll(List.of(
                krwEntry(MEMBER_ID, category, startDate, "1000.00", "오래된 내역"),
                krwEntry(MEMBER_ID, category, startDate.plusDays(1), "2000.00", "같은날 첫 내역"),
                krwEntry(MEMBER_ID, category, startDate.plusDays(1), "3000.00", "같은날 나중 내역"),
                krwEntry(otherMemberId, category, startDate.plusDays(2), "90000.00", "다른 회원 최신 내역")));
        ledgerEntryRepository.flush();

        List<LedgerEntry> entries = ledgerEntryRepository
                .findByMemberIdAndTransactionDateGreaterThanEqualAndTransactionDateLessThanOrderByTransactionDateDescIdDesc(
                        MEMBER_ID, startDate, endDate, PageRequest.of(0, 2));

        assertThat(entries).extracting(LedgerEntry::getMemo).containsExactly("같은날 나중 내역", "같은날 첫 내역");
    }

    @Test
    @DisplayName("단건 조회는 id와 member_id를 함께 사용해 다른 회원 거래를 찾지 않는다")
    void find_by_id_and_member_id_filters_member() {
        UUID otherMemberId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        LedgerEntry myEntry = ledgerEntryRepository.save(krwEntry(MEMBER_ID, category, TODAY, "1000.00", "내 거래"));
        ledgerEntryRepository.save(krwEntry(otherMemberId, category, TODAY, "90000.00", "다른 회원 거래"));
        ledgerEntryRepository.flush();

        assertThat(ledgerEntryRepository.findByIdAndMemberId(myEntry.getId(), MEMBER_ID))
                .isPresent()
                .get()
                .extracting(LedgerEntry::getMemo)
                .isEqualTo("내 거래");
        assertThat(ledgerEntryRepository.findByIdAndMemberId(myEntry.getId(), otherMemberId))
                .isEmpty();
    }

    @Test
    @DisplayName("client_entry_id 조회는 member_id로 격리한다")
    void find_by_member_id_and_client_entry_id_filters_member() {
        UUID otherMemberId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID clientEntryId = UUID.fromString("10000000-0000-0000-0000-000000000001");
        LedgerEntry myEntry = krwEntry(MEMBER_ID, category, TODAY, "1000.00", "내 import 거래");
        myEntry.assignClientEntry(clientEntryId, "a".repeat(64));
        LedgerEntry otherEntry = krwEntry(otherMemberId, category, TODAY, "90000.00", "다른 회원 import 거래");
        otherEntry.assignClientEntry(clientEntryId, "b".repeat(64));
        ledgerEntryRepository.saveAll(List.of(myEntry, otherEntry));
        ledgerEntryRepository.flush();

        assertThat(ledgerEntryRepository.findByMemberIdAndClientEntryId(MEMBER_ID, clientEntryId))
                .isPresent()
                .get()
                .extracting(LedgerEntry::getMemo)
                .isEqualTo("내 import 거래");
        assertThat(ledgerEntryRepository.findByMemberIdAndClientEntryId(otherMemberId, clientEntryId))
                .isPresent()
                .get()
                .extracting(LedgerEntry::getMemo)
                .isEqualTo("다른 회원 import 거래");
    }

    @Test
    @DisplayName("월 리포트 통화 소계는 member_id와 기간으로 격리하고 통화와 거래 유형별로 분리 집계한다")
    void find_monthly_currency_subtotals_filters_member_and_groups_by_currency_and_type() {
        UUID otherMemberId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        LocalDate startDate = LocalDate.of(2026, 4, 1);
        LocalDate endDate = LocalDate.of(2026, 5, 1);
        ExchangeRate usdRate = ExchangeRate.of(CurrencyCode.USD, new BigDecimal("1300.000000"), startDate);

        ledgerEntryRepository.saveAll(List.of(
                foreignEntry(MEMBER_ID, category, CurrencyCode.USD, "100.00", startDate, "내 USD 지출 1", usdRate),
                foreignEntry(
                        MEMBER_ID, category, CurrencyCode.USD, "50.00", startDate.plusDays(1), "내 USD 지출 2", usdRate),
                foreignEntry(MEMBER_ID, incomeCategory, CurrencyCode.USD, "200.00", startDate, "내 USD 수입", usdRate),
                foreignEntry(otherMemberId, category, CurrencyCode.USD, "999.00", startDate, "다른 회원 USD", usdRate),
                foreignEntry(
                        MEMBER_ID,
                        incomeCategory,
                        CurrencyCode.USD,
                        "777.00",
                        startDate.minusDays(1),
                        "전월 USD",
                        usdRate)));
        ledgerEntryRepository.flush();

        List<LedgerEntryRepository.CurrencySubtotalProjection> subtotals =
                ledgerEntryRepository.findCurrencySubtotalsByMemberIdAndTransactionDateRange(
                        MEMBER_ID, startDate, endDate);

        assertThat(subtotals)
                .anySatisfy(subtotal -> {
                    assertThat(subtotal.getCurrencyCode()).isEqualTo(CurrencyCode.USD);
                    assertThat(subtotal.getTransactionType()).isEqualTo(TransactionType.EXPENSE);
                    assertThat(subtotal.getOriginalAmount()).isEqualByComparingTo(new BigDecimal("150.00"));
                    assertThat(subtotal.getKrwAmount()).isEqualByComparingTo(new BigDecimal("195000.00"));
                })
                .anySatisfy(subtotal -> {
                    assertThat(subtotal.getCurrencyCode()).isEqualTo(CurrencyCode.USD);
                    assertThat(subtotal.getTransactionType()).isEqualTo(TransactionType.INCOME);
                    assertThat(subtotal.getOriginalAmount()).isEqualByComparingTo(new BigDecimal("200.00"));
                    assertThat(subtotal.getKrwAmount()).isEqualByComparingTo(new BigDecimal("260000.00"));
                })
                .hasSize(2);
    }

    @Test
    @DisplayName("월 리포트 카테고리 소계는 member_id와 기간으로 격리하고 krw_amount 합계로 집계한다")
    void find_monthly_category_subtotals_filters_member_and_sums_krw_amount() {
        UUID otherMemberId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        LocalDate startDate = LocalDate.of(2026, 4, 1);
        LocalDate endDate = LocalDate.of(2026, 5, 1);
        Category transportCategory = categoryRepository.save(
                new Category(TransactionType.EXPENSE, "TEST_TRANSPORT", "테스트 교통", "Test Transport", "icon-bus", 101));
        ExchangeRate usdRate = ExchangeRate.of(CurrencyCode.USD, new BigDecimal("1300.000000"), startDate);

        ledgerEntryRepository.saveAll(List.of(
                krwEntry(MEMBER_ID, category, startDate, "1000.00", "내 식비 원화"),
                foreignEntry(MEMBER_ID, category, CurrencyCode.USD, "10.00", startDate, "내 식비 외화", usdRate),
                krwEntry(MEMBER_ID, transportCategory, startDate.plusDays(1), "5000.00", "내 교통비"),
                krwEntry(otherMemberId, category, startDate, "90000.00", "다른 회원 식비"),
                krwEntry(MEMBER_ID, category, startDate.minusDays(1), "7000.00", "전월 식비")));
        ledgerEntryRepository.flush();

        List<LedgerEntryRepository.CategorySubtotalProjection> subtotals =
                ledgerEntryRepository.findCategorySubtotalsByMemberIdAndTransactionDateRange(
                        MEMBER_ID, startDate, endDate);

        assertThat(subtotals)
                .anySatisfy(subtotal -> {
                    assertThat(subtotal.getCategoryId()).isEqualTo(category.getId());
                    assertThat(subtotal.getTransactionType()).isEqualTo(TransactionType.EXPENSE);
                    assertThat(subtotal.getCategoryCode()).isEqualTo("TEST_FOOD");
                    assertThat(subtotal.getCategoryDisplayNameKo()).isEqualTo("테스트 식비");
                    assertThat(subtotal.getCategoryDisplayNameEn()).isEqualTo("Test Food");
                    assertThat(subtotal.getKrwAmount()).isEqualByComparingTo(new BigDecimal("14000.00"));
                })
                .anySatisfy(subtotal -> {
                    assertThat(subtotal.getCategoryId()).isEqualTo(transportCategory.getId());
                    assertThat(subtotal.getCategoryCode()).isEqualTo("TEST_TRANSPORT");
                    assertThat(subtotal.getCategoryDisplayNameKo()).isEqualTo("테스트 교통");
                    assertThat(subtotal.getCategoryDisplayNameEn()).isEqualTo("Test Transport");
                    assertThat(subtotal.getKrwAmount()).isEqualByComparingTo(new BigDecimal("5000.00"));
                })
                .hasSize(2);
    }

    @Test
    @DisplayName("changes 첫 페이지와 커서 페이지는 member_id로 격리해 updated_at, id 오름차순으로 전량 회수한다")
    void find_changes_pages_by_member_id_after_updated_at_id_cursor_without_duplicates_or_omissions() {
        UUID otherMemberId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        LocalDateTime firstUpdatedAt = LocalDateTime.of(2026, 4, 6, 9, 0);
        LocalDateTime secondUpdatedAt = LocalDateTime.of(2026, 4, 6, 9, 1);
        LocalDateTime thirdUpdatedAt = LocalDateTime.of(2026, 4, 6, 9, 2);
        LedgerEntry first = krwEntry(MEMBER_ID, category, TODAY, "1000.00", "changes 1");
        LedgerEntry second = krwEntry(MEMBER_ID, category, TODAY, "2000.00", "changes 2");
        LedgerEntry third = krwEntry(MEMBER_ID, category, TODAY, "3000.00", "changes 3");
        LedgerEntry fourth = krwEntry(MEMBER_ID, category, TODAY, "4000.00", "changes 4");
        LedgerEntry otherMember = krwEntry(otherMemberId, category, TODAY, "90000.00", "other member changes");
        ledgerEntryRepository.saveAll(List.of(first, second, third, fourth, otherMember));
        ledgerEntryRepository.flush();
        setUpdatedAt(first, firstUpdatedAt);
        setUpdatedAt(second, secondUpdatedAt);
        setUpdatedAt(third, thirdUpdatedAt);
        setUpdatedAt(fourth, thirdUpdatedAt.plusMinutes(1));
        setUpdatedAt(otherMember, secondUpdatedAt);
        entityManager.clear();

        List<LedgerEntry> firstPage =
                ledgerEntryRepository.findChangesFirstPageByMemberId(MEMBER_ID, PageRequest.of(0, 2));
        List<LedgerEntry> recovered = new ArrayList<>(firstPage);
        while (!firstPage.isEmpty()) {
            LedgerEntry cursor = firstPage.getLast();
            firstPage = ledgerEntryRepository.findChangesPageByMemberIdAfterCursor(
                    MEMBER_ID, cursor.getUpdatedAt(), cursor.getId(), PageRequest.of(0, 2));
            recovered.addAll(firstPage);
        }
        List<LedgerEntry> otherMemberPage =
                ledgerEntryRepository.findChangesFirstPageByMemberId(otherMemberId, PageRequest.of(0, 10));

        assertThat(recovered)
                .extracting(LedgerEntry::getId)
                .containsExactly(first.getId(), second.getId(), third.getId(), fourth.getId());
        assertThat(recovered).doesNotHaveDuplicates();
        assertThat(recovered)
                .isSortedAccordingTo(
                        Comparator.comparing(LedgerEntry::getUpdatedAt).thenComparing(LedgerEntry::getId));
        assertThat(otherMemberPage).extracting(LedgerEntry::getId).containsExactly(otherMember.getId());
    }

    @Test
    @DisplayName("changes 커서는 동일 updated_at 경계에서도 id 타이브레이커로 중복과 누락 없이 분할한다")
    void find_changes_page_after_cursor_uses_id_tiebreaker_for_same_updated_at_boundary() {
        LocalDateTime sameUpdatedAt = LocalDateTime.of(2026, 4, 6, 10, 0);
        LedgerEntry first = krwEntry(MEMBER_ID, category, TODAY, "1000.00", "same updatedAt 1");
        LedgerEntry second = krwEntry(MEMBER_ID, category, TODAY, "2000.00", "same updatedAt 2");
        LedgerEntry third = krwEntry(MEMBER_ID, category, TODAY, "3000.00", "same updatedAt 3");
        ledgerEntryRepository.saveAll(List.of(first, second, third));
        ledgerEntryRepository.flush();
        setUpdatedAt(first, sameUpdatedAt);
        setUpdatedAt(second, sameUpdatedAt);
        setUpdatedAt(third, sameUpdatedAt);
        entityManager.clear();

        List<LedgerEntry> firstPage =
                ledgerEntryRepository.findChangesFirstPageByMemberId(MEMBER_ID, PageRequest.of(0, 2));
        LedgerEntry cursor = firstPage.getLast();
        List<LedgerEntry> nextPage = ledgerEntryRepository.findChangesPageByMemberIdAfterCursor(
                MEMBER_ID, cursor.getUpdatedAt(), cursor.getId(), PageRequest.of(0, 2));

        assertThat(firstPage).extracting(LedgerEntry::getId).containsExactly(first.getId(), second.getId());
        assertThat(nextPage).extracting(LedgerEntry::getId).containsExactly(third.getId());
    }

    private LedgerEntry krwEntry(
            UUID memberId, Category entryCategory, LocalDate transactionDate, String amount, String memo) {
        return LedgerEntry.of(
                memberId,
                entryCategory,
                asset,
                new BigDecimal(amount),
                CurrencyCode.KRW,
                transactionDate,
                memo,
                null,
                FIXED_CLOCK);
    }

    private LedgerEntry foreignEntry(
            UUID memberId,
            Category entryCategory,
            CurrencyCode currencyCode,
            String amount,
            LocalDate transactionDate,
            String memo,
            ExchangeRate exchangeRate) {
        return LedgerEntry.of(
                memberId,
                entryCategory,
                asset,
                new BigDecimal(amount),
                currencyCode,
                transactionDate,
                memo,
                exchangeRate,
                FIXED_CLOCK);
    }

    private void setUpdatedAt(LedgerEntry entry, LocalDateTime updatedAt) {
        jdbcTemplate.update("update ledger_entry set updated_at = ? where id = ?", updatedAt, entry.getId());
    }
}

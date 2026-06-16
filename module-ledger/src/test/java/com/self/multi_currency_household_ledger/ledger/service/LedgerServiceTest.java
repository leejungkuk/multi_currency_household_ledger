package com.self.multi_currency_household_ledger.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import com.self.multi_currency_household_ledger.common.exception.BusinessException;
import com.self.multi_currency_household_ledger.exchange.domain.CurrencyCode;
import com.self.multi_currency_household_ledger.exchange.domain.ExchangeRate;
import com.self.multi_currency_household_ledger.exchange.service.ExchangeRateService;
import com.self.multi_currency_household_ledger.ledger.domain.Asset;
import com.self.multi_currency_household_ledger.ledger.domain.AssetRepository;
import com.self.multi_currency_household_ledger.ledger.domain.Category;
import com.self.multi_currency_household_ledger.ledger.domain.CategoryRepository;
import com.self.multi_currency_household_ledger.ledger.domain.LedgerEntry;
import com.self.multi_currency_household_ledger.ledger.domain.LedgerEntryRepository;
import com.self.multi_currency_household_ledger.ledger.domain.TransactionType;
import com.self.multi_currency_household_ledger.ledger.dto.CategoryResponse;
import com.self.multi_currency_household_ledger.ledger.dto.CreateLedgerEntryRequest;
import com.self.multi_currency_household_ledger.ledger.dto.LedgerEntryResponse;
import com.self.multi_currency_household_ledger.ledger.dto.LedgerMonthlySummaryResponse;
import com.self.multi_currency_household_ledger.ledger.dto.LedgerReportResponse;
import com.self.multi_currency_household_ledger.ledger.exception.LedgerErrorCode;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class LedgerServiceTest {

    private static final UUID MEMBER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final LocalDate TODAY = LocalDate.of(2026, 4, 6);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-04-05T15:00:00Z"), KST);

    private LedgerService ledgerService;

    @Mock
    private LedgerEntryRepository ledgerEntryRepository;

    @Mock
    private CategoryRepository categoryRepository;

    @Mock
    private AssetRepository assetRepository;

    @Mock
    private ExchangeRateService exchangeRateService;

    private Category category;
    private Asset asset;

    @BeforeEach
    void setUp() {
        ledgerService = new LedgerService(
                ledgerEntryRepository, categoryRepository, assetRepository, exchangeRateService, FIXED_CLOCK);
        category = new Category(TransactionType.EXPENSE, "FOOD", "식비", "icon", 1, Category.SYSTEM_OWNER_ID);
        asset = new Asset("CASH", "현금", "icon", 1, Asset.SYSTEM_OWNER_ID);
    }

    // 원화 거래 시 환율 조회를 건너뛰는지 확인한다.
    @Test
    @DisplayName("원화(KRW) 거래 시 환율 조회 없이 생성된다")
    void create_ledger_entry_krw_skip_exchange_rate() {
        CreateLedgerEntryRequest request =
                new CreateLedgerEntryRequest(BigDecimal.valueOf(5000), CurrencyCode.KRW, 1L, 1L, TODAY, "커피");

        given(categoryRepository.findByIdAndOwnerMemberId(eq(1L), eq(Category.SYSTEM_OWNER_ID)))
                .willReturn(Optional.of(category));
        given(assetRepository.findByIdAndOwnerMemberId(eq(1L), eq(Asset.SYSTEM_OWNER_ID)))
                .willReturn(Optional.of(asset));
        given(ledgerEntryRepository.save(any(LedgerEntry.class))).willAnswer(invocation -> invocation.getArgument(0));

        LedgerEntryResponse response = ledgerService.create(request, MEMBER_ID);

        assertThat(response.originalAmount()).isEqualByComparingTo(BigDecimal.valueOf(5000));
        assertThat(response.krwAmount()).isEqualByComparingTo(BigDecimal.valueOf(5000));
        assertThat(response.appliedRate()).isEqualByComparingTo(BigDecimal.ONE);
        ArgumentCaptor<LedgerEntry> entryCaptor = ArgumentCaptor.forClass(LedgerEntry.class);
        then(ledgerEntryRepository).should().save(entryCaptor.capture());
        assertThat(entryCaptor.getValue().getMemberId()).isEqualTo(MEMBER_ID);
    }

    // 외화 거래 시 환율을 적용하여 원화 금액을 계산하는지 확인한다.
    @Test
    @DisplayName("외화 거래 시 과거 또는 현재 환율이 적용되어 계산된다")
    void create_ledger_entry_foreign_currency() {
        CreateLedgerEntryRequest request =
                new CreateLedgerEntryRequest(BigDecimal.valueOf(100), CurrencyCode.USD, 1L, 1L, TODAY, "점심 식사");

        ExchangeRate exchangeRate = ExchangeRate.of(CurrencyCode.USD, BigDecimal.valueOf(1300), TODAY);

        given(categoryRepository.findByIdAndOwnerMemberId(eq(1L), eq(Category.SYSTEM_OWNER_ID)))
                .willReturn(Optional.of(category));
        given(assetRepository.findByIdAndOwnerMemberId(eq(1L), eq(Asset.SYSTEM_OWNER_ID)))
                .willReturn(Optional.of(asset));
        given(exchangeRateService.getRateOnOrBefore(any(), any())).willReturn(exchangeRate);
        given(ledgerEntryRepository.save(any(LedgerEntry.class))).willAnswer(invocation -> invocation.getArgument(0));

        LedgerEntryResponse response = ledgerService.create(request, MEMBER_ID);

        assertThat(response.originalAmount()).isEqualByComparingTo(BigDecimal.valueOf(100));
        assertThat(response.krwAmount()).isEqualByComparingTo(BigDecimal.valueOf(130000));
        assertThat(response.appliedRate()).isEqualByComparingTo(BigDecimal.valueOf(1300));
    }

    // 외화 거래 시 미래 날짜가 주어지면 에러가 발생하는지 확인한다.
    @Test
    @DisplayName("외화 거래 시 미래 날짜를 입력하면 예외가 발생한다")
    void create_ledger_entry_fails_for_future_date_foreign_currency() {
        CreateLedgerEntryRequest request = new CreateLedgerEntryRequest(
                BigDecimal.valueOf(100), CurrencyCode.USD, 1L, 1L, TODAY.plusDays(1), "점심 식사");

        given(categoryRepository.findByIdAndOwnerMemberId(eq(1L), eq(Category.SYSTEM_OWNER_ID)))
                .willReturn(Optional.of(category));
        given(assetRepository.findByIdAndOwnerMemberId(eq(1L), eq(Asset.SYSTEM_OWNER_ID)))
                .willReturn(Optional.of(asset));
        given(exchangeRateService.getRateOnOrBefore(any(), any()))
                .willReturn(ExchangeRate.of(CurrencyCode.USD, BigDecimal.valueOf(1300), TODAY));

        assertThatThrownBy(() -> ledgerService.create(request, MEMBER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(LedgerErrorCode.INVALID_FUTURE_DATE.getCode());
    }

    @Test
    @DisplayName("거래 수정은 member_id 술어로 조회한 뒤 외화 환율을 거래일 기준으로 재해석한다")
    void update_foreign_currency_uses_member_predicate_and_reinterprets_exchange_rate() {
        LedgerEntry entry = LedgerEntry.of(
                MEMBER_ID,
                category,
                asset,
                new BigDecimal("100.00"),
                CurrencyCode.USD,
                TODAY,
                "기존 메모",
                ExchangeRate.of(CurrencyCode.USD, new BigDecimal("1300.000000"), TODAY.minusDays(1)),
                FIXED_CLOCK);
        Category incomeCategory = new Category(TransactionType.INCOME, "SALARY", "급여", "icon-salary", 2, 1L);
        Asset card = new Asset("CARD", "카드", "icon-card", 2, Asset.SYSTEM_OWNER_ID);
        CreateLedgerEntryRequest request =
                new CreateLedgerEntryRequest(new BigDecimal("50.00"), CurrencyCode.EUR, 2L, 2L, TODAY, "수정 메모");
        ExchangeRate newRate = ExchangeRate.of(CurrencyCode.EUR, new BigDecimal("1400.000000"), TODAY);

        given(ledgerEntryRepository.findByIdAndMemberId(1L, MEMBER_ID)).willReturn(Optional.of(entry));
        given(categoryRepository.findByIdAndOwnerMemberId(eq(2L), eq(Category.SYSTEM_OWNER_ID)))
                .willReturn(Optional.of(incomeCategory));
        given(assetRepository.findByIdAndOwnerMemberId(eq(2L), eq(Asset.SYSTEM_OWNER_ID)))
                .willReturn(Optional.of(card));
        given(exchangeRateService.getRateOnOrBefore(CurrencyCode.EUR, TODAY)).willReturn(newRate);

        LedgerEntryResponse response = ledgerService.update(1L, request, MEMBER_ID);

        assertThat(response.transactionType()).isEqualTo(TransactionType.INCOME);
        assertThat(response.originalAmount()).isEqualByComparingTo(new BigDecimal("50.00"));
        assertThat(response.currencyCode()).isEqualTo(CurrencyCode.EUR);
        assertThat(response.appliedRate()).isEqualByComparingTo(new BigDecimal("1400.000000"));
        assertThat(response.rateBaseDate()).isEqualTo(TODAY);
        assertThat(response.krwAmount()).isEqualByComparingTo(new BigDecimal("70000.00"));
        assertThat(response.memo()).isEqualTo("수정 메모");
        then(ledgerEntryRepository).should().findByIdAndMemberId(1L, MEMBER_ID);
    }

    @Test
    @DisplayName("타 회원 또는 없는 거래 수정은 카탈로그 조회 없이 404 예외를 던진다")
    void update_missing_or_other_member_entry_throws_not_found() {
        CreateLedgerEntryRequest request =
                new CreateLedgerEntryRequest(BigDecimal.valueOf(5000), CurrencyCode.KRW, 1L, 1L, TODAY, "커피");
        given(ledgerEntryRepository.findByIdAndMemberId(99L, MEMBER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> ledgerService.update(99L, request, MEMBER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(LedgerErrorCode.LEDGER_ENTRY_NOT_FOUND.getCode());
        then(categoryRepository).shouldHaveNoInteractions();
        then(assetRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("KRW 거래 수정은 환율 조회 없이 rate=1, 기준일 null, 원화 금액=원금으로 교체한다")
    void update_krw_skips_exchange_rate_and_resets_snapshot() {
        LedgerEntry entry = LedgerEntry.of(
                MEMBER_ID,
                category,
                asset,
                new BigDecimal("100.00"),
                CurrencyCode.USD,
                TODAY,
                "기존 메모",
                ExchangeRate.of(CurrencyCode.USD, new BigDecimal("1300.000000"), TODAY),
                FIXED_CLOCK);
        CreateLedgerEntryRequest request =
                new CreateLedgerEntryRequest(new BigDecimal("5000.00"), CurrencyCode.KRW, 1L, 1L, TODAY, null);

        given(ledgerEntryRepository.findByIdAndMemberId(1L, MEMBER_ID)).willReturn(Optional.of(entry));
        given(categoryRepository.findByIdAndOwnerMemberId(eq(1L), eq(Category.SYSTEM_OWNER_ID)))
                .willReturn(Optional.of(category));
        given(assetRepository.findByIdAndOwnerMemberId(eq(1L), eq(Asset.SYSTEM_OWNER_ID)))
                .willReturn(Optional.of(asset));

        LedgerEntryResponse response = ledgerService.update(1L, request, MEMBER_ID);

        assertThat(response.appliedRate()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(response.rateBaseDate()).isNull();
        assertThat(response.krwAmount()).isEqualByComparingTo(new BigDecimal("5000.00"));
        then(exchangeRateService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("거래 삭제는 member_id 술어로 조회한 거래만 삭제한다")
    void delete_uses_member_predicate() {
        LedgerEntry entry = LedgerEntry.of(
                MEMBER_ID, category, asset, BigDecimal.valueOf(5000), CurrencyCode.KRW, TODAY, "커피", null, FIXED_CLOCK);
        given(ledgerEntryRepository.findByIdAndMemberId(1L, MEMBER_ID)).willReturn(Optional.of(entry));

        ledgerService.delete(1L, MEMBER_ID);

        then(ledgerEntryRepository).should().findByIdAndMemberId(1L, MEMBER_ID);
        then(ledgerEntryRepository).should().delete(entry);
    }

    @Test
    @DisplayName("타 회원 또는 없는 거래 삭제는 무음 처리하지 않고 404 예외를 던진다")
    void delete_missing_or_other_member_entry_throws_not_found() {
        given(ledgerEntryRepository.findByIdAndMemberId(99L, MEMBER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> ledgerService.delete(99L, MEMBER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(LedgerErrorCode.LEDGER_ENTRY_NOT_FOUND.getCode());
    }

    @Test
    @DisplayName("월 요약은 member_id와 월 범위로 수입, 지출, 순액을 계산한다")
    void get_monthly_summary_calculates_income_expense_and_net_total() {
        LocalDate startDate = LocalDate.of(2026, 4, 1);
        LocalDate endDate = LocalDate.of(2026, 5, 1);
        given(ledgerEntryRepository.sumKrwAmountByMemberIdAndTransactionTypeAndTransactionDateRange(
                        MEMBER_ID, TransactionType.INCOME, startDate, endDate))
                .willReturn(new BigDecimal("3000.00"));
        given(ledgerEntryRepository.sumKrwAmountByMemberIdAndTransactionTypeAndTransactionDateRange(
                        MEMBER_ID, TransactionType.EXPENSE, startDate, endDate))
                .willReturn(new BigDecimal("1200.00"));

        LedgerMonthlySummaryResponse response = ledgerService.getMonthlySummary(MEMBER_ID, 2026, 4);

        assertThat(response.income()).isEqualByComparingTo(new BigDecimal("3000.00"));
        assertThat(response.expense()).isEqualByComparingTo(new BigDecimal("1200.00"));
        assertThat(response.total()).isEqualByComparingTo(new BigDecimal("1800.00"));
    }

    @Test
    @DisplayName("월 거래 목록은 member_id와 월 범위, 서버 하드 캡으로 조회한다")
    void get_monthly_entries_uses_member_period_and_hard_cap() {
        LocalDate startDate = LocalDate.of(2026, 4, 1);
        LocalDate endDate = LocalDate.of(2026, 5, 1);
        given(ledgerEntryRepository
                        .findByMemberIdAndTransactionDateGreaterThanEqualAndTransactionDateLessThanOrderByTransactionDateDescIdDesc(
                                eq(MEMBER_ID), eq(startDate), eq(endDate), any(Pageable.class)))
                .willReturn(List.of());

        List<LedgerEntryResponse> responses = ledgerService.getMonthlyEntries(MEMBER_ID, 2026, 4);

        assertThat(responses).isEmpty();
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        then(ledgerEntryRepository)
                .should()
                .findByMemberIdAndTransactionDateGreaterThanEqualAndTransactionDateLessThanOrderByTransactionDateDescIdDesc(
                        eq(MEMBER_ID), eq(startDate), eq(endDate), pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(500);
    }

    @Test
    @DisplayName("월 리포트는 member_id와 월 범위로 통화별, 카테고리별 소계를 조회한다")
    void get_monthly_report_uses_member_period_and_maps_subtotals() {
        LocalDate startDate = LocalDate.of(2026, 4, 1);
        LocalDate endDate = LocalDate.of(2026, 5, 1);
        given(ledgerEntryRepository.findCurrencySubtotalsByMemberIdAndTransactionDateRange(
                        MEMBER_ID, startDate, endDate))
                .willReturn(List.of(new CurrencySubtotalStub(
                        CurrencyCode.USD,
                        TransactionType.EXPENSE,
                        new BigDecimal("150.00"),
                        new BigDecimal("195000.00"))));
        given(ledgerEntryRepository.findCategorySubtotalsByMemberIdAndTransactionDateRange(
                        MEMBER_ID, startDate, endDate))
                .willReturn(List.of(new CategorySubtotalStub(
                        1L, TransactionType.EXPENSE, "FOOD", "식비", "icon-food", 1, new BigDecimal("14000.00"))));

        LedgerReportResponse response = ledgerService.getMonthlyReport(MEMBER_ID, 2026, 4);

        assertThat(response.currencySubtotals())
                .containsExactly(new LedgerReportResponse.CurrencySubtotal(
                        CurrencyCode.USD,
                        TransactionType.EXPENSE,
                        new BigDecimal("150.00"),
                        new BigDecimal("195000.00")));
        assertThat(response.categorySubtotals())
                .containsExactly(new LedgerReportResponse.CategorySubtotal(
                        new CategoryResponse(1L, "FOOD", "식비", "icon-food", 1),
                        TransactionType.EXPENSE,
                        new BigDecimal("14000.00")));
    }

    private record CurrencySubtotalStub(
            CurrencyCode currencyCode, TransactionType transactionType, BigDecimal originalAmount, BigDecimal krwAmount)
            implements LedgerEntryRepository.CurrencySubtotalProjection {

        @Override
        public CurrencyCode getCurrencyCode() {
            return currencyCode;
        }

        @Override
        public TransactionType getTransactionType() {
            return transactionType;
        }

        @Override
        public BigDecimal getOriginalAmount() {
            return originalAmount;
        }

        @Override
        public BigDecimal getKrwAmount() {
            return krwAmount;
        }
    }

    private record CategorySubtotalStub(
            Long categoryId,
            TransactionType transactionType,
            String categoryCode,
            String categoryDisplayName,
            String categoryIcon,
            Integer categorySortOrder,
            BigDecimal krwAmount)
            implements LedgerEntryRepository.CategorySubtotalProjection {

        @Override
        public Long getCategoryId() {
            return categoryId;
        }

        @Override
        public TransactionType getTransactionType() {
            return transactionType;
        }

        @Override
        public String getCategoryCode() {
            return categoryCode;
        }

        @Override
        public String getCategoryDisplayName() {
            return categoryDisplayName;
        }

        @Override
        public String getCategoryIcon() {
            return categoryIcon;
        }

        @Override
        public Integer getCategorySortOrder() {
            return categorySortOrder;
        }

        @Override
        public BigDecimal getKrwAmount() {
            return krwAmount;
        }
    }
}

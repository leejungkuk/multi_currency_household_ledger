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
import com.self.multi_currency_household_ledger.ledger.dto.CreateLedgerEntryRequest;
import com.self.multi_currency_household_ledger.ledger.dto.LedgerEntryResponse;
import com.self.multi_currency_household_ledger.ledger.dto.LedgerMonthlySummaryResponse;
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
}

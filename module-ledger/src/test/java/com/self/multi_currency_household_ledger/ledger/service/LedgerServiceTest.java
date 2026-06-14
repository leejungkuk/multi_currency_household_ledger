package com.self.multi_currency_household_ledger.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
import com.self.multi_currency_household_ledger.ledger.exception.LedgerErrorCode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LedgerServiceTest {

    private static final UUID MEMBER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @InjectMocks
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
        category = new Category(TransactionType.EXPENSE, "FOOD", "식비", "icon", 1, 1L);
        asset = new Asset("CASH", "현금", "icon", 1, 1L);
    }

    // 원화 거래 시 환율 조회를 건너뛰는지 확인한다.
    @Test
    @DisplayName("원화(KRW) 거래 시 환율 조회 없이 생성된다")
    void create_ledger_entry_krw_skip_exchange_rate() {
        CreateLedgerEntryRequest request = new CreateLedgerEntryRequest(
                BigDecimal.valueOf(5000), CurrencyCode.KRW, 1L, 1L, LocalDate.now(ZoneId.of("Asia/Seoul")), "커피");

        given(categoryRepository.findByIdAndOwnerMemberIdIn(any(), any())).willReturn(Optional.of(category));
        given(assetRepository.findByIdAndOwnerMemberIdIn(any(), any())).willReturn(Optional.of(asset));
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
        CreateLedgerEntryRequest request = new CreateLedgerEntryRequest(
                BigDecimal.valueOf(100), CurrencyCode.USD, 1L, 1L, LocalDate.now(ZoneId.of("Asia/Seoul")), "점심 식사");

        ExchangeRate exchangeRate =
                ExchangeRate.of(CurrencyCode.USD, BigDecimal.valueOf(1300), LocalDate.now(ZoneId.of("Asia/Seoul")));

        given(categoryRepository.findByIdAndOwnerMemberIdIn(any(), any())).willReturn(Optional.of(category));
        given(assetRepository.findByIdAndOwnerMemberIdIn(any(), any())).willReturn(Optional.of(asset));
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
                BigDecimal.valueOf(100),
                CurrencyCode.USD,
                1L,
                1L,
                LocalDate.now(ZoneId.of("Asia/Seoul")).plusDays(1),
                "점심 식사");

        given(categoryRepository.findByIdAndOwnerMemberIdIn(any(), any())).willReturn(Optional.of(category));
        given(assetRepository.findByIdAndOwnerMemberIdIn(any(), any())).willReturn(Optional.of(asset));
        given(exchangeRateService.getRateOnOrBefore(any(), any()))
                .willReturn(ExchangeRate.of(
                        CurrencyCode.USD, BigDecimal.valueOf(1300), LocalDate.now(ZoneId.of("Asia/Seoul"))));

        assertThatThrownBy(() -> ledgerService.create(request, MEMBER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(LedgerErrorCode.INVALID_FUTURE_DATE.getCode());
    }
}

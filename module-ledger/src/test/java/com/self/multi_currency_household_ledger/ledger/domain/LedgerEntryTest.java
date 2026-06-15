package com.self.multi_currency_household_ledger.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.self.multi_currency_household_ledger.common.exception.BusinessException;
import com.self.multi_currency_household_ledger.exchange.domain.CurrencyCode;
import com.self.multi_currency_household_ledger.exchange.domain.ExchangeRate;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LedgerEntryTest {

    private static final UUID MEMBER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final LocalDate TODAY = LocalDate.of(2026, 4, 6);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-04-05T15:00:00Z"), KST);

    private Category category;
    private Asset asset;

    @BeforeEach
    void setUp() {
        category = new Category(TransactionType.EXPENSE, "FOOD", "식비", "icon-food", 1, 1L);
        asset = new Asset("CASH", "현금", "icon-cash", 1, 1L);
    }

    // 정상적으로 가계부 내역이 생성되는지 확인한다.
    @Test
    @DisplayName("정상적인 데이터로 가계부 내역을 생성할 수 있다")
    void create_ledger_entry_success() {
        ExchangeRate exchangeRate = ExchangeRate.of(CurrencyCode.USD, BigDecimal.valueOf(1300), TODAY);
        LedgerEntry entry = LedgerEntry.of(
                MEMBER_ID,
                category,
                asset,
                BigDecimal.valueOf(100),
                CurrencyCode.USD,
                TODAY,
                "점심 식사",
                exchangeRate,
                FIXED_CLOCK);

        assertThat(entry.getOriginalAmount()).isEqualByComparingTo(BigDecimal.valueOf(100));
        assertThat(entry.getMemberId()).isEqualTo(MEMBER_ID);
        assertThat(entry.getKrwAmount()).isEqualByComparingTo(BigDecimal.valueOf(130000));
        assertThat(entry.getTransactionType()).isEqualTo(TransactionType.EXPENSE);
    }

    // 금액이 0 이하일 경우 예외가 발생하는지 확인한다.
    @Test
    @DisplayName("금액이 0 이하일 경우 예외가 발생한다")
    void create_ledger_entry_fails_when_amount_is_zero_or_negative() {
        ExchangeRate exchangeRate = ExchangeRate.of(CurrencyCode.USD, BigDecimal.valueOf(1300), TODAY);
        assertThatThrownBy(() -> LedgerEntry.of(
                        MEMBER_ID,
                        category,
                        asset,
                        BigDecimal.ZERO,
                        CurrencyCode.USD,
                        TODAY,
                        "점심 식사",
                        exchangeRate,
                        FIXED_CLOCK))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("금액이 99,999,999를 초과하면 예외가 발생한다")
    void create_ledger_entry_fails_when_amount_exceeds_maximum() {
        assertThatThrownBy(() -> LedgerEntry.of(
                        MEMBER_ID,
                        category,
                        asset,
                        new BigDecimal("100000000.00"),
                        CurrencyCode.KRW,
                        TODAY,
                        "큰 금액",
                        null,
                        FIXED_CLOCK))
                .isInstanceOf(BusinessException.class);
    }

    // 외화 거래 시 미래 날짜로 생성할 수 없는지 확인한다.
    @Test
    @DisplayName("외화 거래의 경우 미래 날짜로 생성할 수 없다")
    void create_ledger_entry_fails_when_future_date_for_foreign_currency() {
        ExchangeRate exchangeRate = ExchangeRate.of(CurrencyCode.USD, BigDecimal.valueOf(1300), TODAY);
        assertThatThrownBy(() -> LedgerEntry.of(
                        MEMBER_ID,
                        category,
                        asset,
                        BigDecimal.valueOf(100),
                        CurrencyCode.USD,
                        TODAY.plusDays(1),
                        "점심 식사",
                        exchangeRate,
                        FIXED_CLOCK))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("외화 거래 재계산은 환율, 기준일, 원화 금액을 함께 갱신한다")
    void recalculate_foreign_currency_updates_rate_snapshot_together() {
        ExchangeRate oldRate = ExchangeRate.of(CurrencyCode.USD, new BigDecimal("1300.000000"), TODAY.minusDays(1));
        LedgerEntry entry = LedgerEntry.of(
                MEMBER_ID,
                category,
                asset,
                new BigDecimal("100.00"),
                CurrencyCode.USD,
                TODAY,
                "점심 식사",
                oldRate,
                FIXED_CLOCK);

        boolean recalculated = entry.recalculate(new BigDecimal("1320.000000"), TODAY);

        assertThat(recalculated).isTrue();
        assertThat(entry.getAppliedRate()).isEqualByComparingTo(new BigDecimal("1320.000000"));
        assertThat(entry.getRateBaseDate()).isEqualTo(TODAY);
        assertThat(entry.getKrwAmount()).isEqualByComparingTo(new BigDecimal("132000.00"));
    }

    @Test
    @DisplayName("이미 적용 가능한 최신 기준일을 쓰는 외화 거래는 재계산하지 않는다")
    void recalculate_foreign_currency_is_noop_when_base_date_is_current() {
        ExchangeRate currentRate = ExchangeRate.of(CurrencyCode.USD, new BigDecimal("1320.000000"), TODAY);
        LedgerEntry entry = LedgerEntry.of(
                MEMBER_ID,
                category,
                asset,
                new BigDecimal("100.00"),
                CurrencyCode.USD,
                TODAY,
                "점심 식사",
                currentRate,
                FIXED_CLOCK);

        boolean recalculated = entry.recalculate(new BigDecimal("1320.000000"), TODAY);

        assertThat(recalculated).isFalse();
        assertThat(entry.getAppliedRate()).isEqualByComparingTo(new BigDecimal("1320.000000"));
        assertThat(entry.getRateBaseDate()).isEqualTo(TODAY);
        assertThat(entry.getKrwAmount()).isEqualByComparingTo(new BigDecimal("132000.00"));
    }

    @Test
    @DisplayName("KRW 거래는 재계산 요청이 와도 불변이다")
    void recalculate_krw_entry_is_noop() {
        LedgerEntry entry = LedgerEntry.of(
                MEMBER_ID,
                category,
                asset,
                new BigDecimal("5000.00"),
                CurrencyCode.KRW,
                TODAY,
                "커피",
                null,
                FIXED_CLOCK);

        boolean recalculated = entry.recalculate(new BigDecimal("1320.000000"), TODAY);

        assertThat(recalculated).isFalse();
        assertThat(entry.getAppliedRate()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(entry.getRateBaseDate()).isNull();
        assertThat(entry.getKrwAmount()).isEqualByComparingTo(new BigDecimal("5000.00"));
    }
}

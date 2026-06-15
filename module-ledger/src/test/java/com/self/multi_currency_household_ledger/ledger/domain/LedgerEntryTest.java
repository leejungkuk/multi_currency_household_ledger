package com.self.multi_currency_household_ledger.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.self.multi_currency_household_ledger.common.exception.BusinessException;
import com.self.multi_currency_household_ledger.exchange.domain.CurrencyCode;
import com.self.multi_currency_household_ledger.exchange.domain.ExchangeRate;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LedgerEntryTest {

    private static final UUID MEMBER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

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
        ExchangeRate exchangeRate =
                ExchangeRate.of(CurrencyCode.USD, BigDecimal.valueOf(1300), LocalDate.now(ZoneId.of("Asia/Seoul")));
        LedgerEntry entry = LedgerEntry.of(
                MEMBER_ID,
                category,
                asset,
                BigDecimal.valueOf(100),
                CurrencyCode.USD,
                LocalDate.now(ZoneId.of("Asia/Seoul")),
                "점심 식사",
                exchangeRate);

        assertThat(entry.getOriginalAmount()).isEqualByComparingTo(BigDecimal.valueOf(100));
        assertThat(entry.getMemberId()).isEqualTo(MEMBER_ID);
        assertThat(entry.getKrwAmount()).isEqualByComparingTo(BigDecimal.valueOf(130000));
        assertThat(entry.getTransactionType()).isEqualTo(TransactionType.EXPENSE);
    }

    // 금액이 0 이하일 경우 예외가 발생하는지 확인한다.
    @Test
    @DisplayName("금액이 0 이하일 경우 예외가 발생한다")
    void create_ledger_entry_fails_when_amount_is_zero_or_negative() {
        ExchangeRate exchangeRate =
                ExchangeRate.of(CurrencyCode.USD, BigDecimal.valueOf(1300), LocalDate.now(ZoneId.of("Asia/Seoul")));
        assertThatThrownBy(() -> LedgerEntry.of(
                        MEMBER_ID,
                        category,
                        asset,
                        BigDecimal.ZERO,
                        CurrencyCode.USD,
                        LocalDate.now(ZoneId.of("Asia/Seoul")),
                        "점심 식사",
                        exchangeRate))
                .isInstanceOf(BusinessException.class);
    }

    // 외화 거래 시 미래 날짜로 생성할 수 없는지 확인한다.
    @Test
    @DisplayName("외화 거래의 경우 미래 날짜로 생성할 수 없다")
    void create_ledger_entry_fails_when_future_date_for_foreign_currency() {
        ExchangeRate exchangeRate =
                ExchangeRate.of(CurrencyCode.USD, BigDecimal.valueOf(1300), LocalDate.now(ZoneId.of("Asia/Seoul")));
        assertThatThrownBy(() -> LedgerEntry.of(
                        MEMBER_ID,
                        category,
                        asset,
                        BigDecimal.valueOf(100),
                        CurrencyCode.USD,
                        LocalDate.now(ZoneId.of("Asia/Seoul")).plusDays(1),
                        "점심 식사",
                        exchangeRate))
                .isInstanceOf(BusinessException.class);
    }
}

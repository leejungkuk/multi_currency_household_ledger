package com.self.multi_currency_household_ledger.exchange.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.self.multi_currency_household_ledger.exchange.domain.CurrencyCode;
import com.self.multi_currency_household_ledger.exchange.domain.ExchangeRate;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ExchangeRateApiResponseTest {

    @Test
    @DisplayName("toDomain()은 ExchangeRate 도메인을 생성한다")
    void toDomain_creates_exchange_rate() {
        var response = new ExchangeRateApiResponse("USD", "미 달러", new BigDecimal("1300.00"));
        LocalDate date = LocalDate.of(2026, 4, 3);

        ExchangeRate rate = response.toDomain(date);

        assertThat(rate.getCurrencyCode()).isEqualTo(CurrencyCode.USD);
        assertThat(rate.getDealBasRate()).isEqualByComparingTo(new BigDecimal("1300.00"));
        assertThat(rate.getBaseDate()).isEqualTo(date);
    }

    @Test
    @DisplayName("JPY(100) 코드도 올바르게 도메인으로 변환한다")
    void toDomain_handles_jpy() {
        var response = new ExchangeRateApiResponse("JPY(100)", "일본 엔", new BigDecimal("900.00"));

        ExchangeRate rate = response.toDomain(LocalDate.of(2026, 4, 3));

        assertThat(rate.getCurrencyCode()).isEqualTo(CurrencyCode.JPY);
    }

    @Test
    @DisplayName("지원하지 않는 통화 코드면 예외를 던진다")
    void toDomain_throws_for_unsupported_code() {
        var response = new ExchangeRateApiResponse("AED", "디르함", new BigDecimal("354.05"));

        assertThatThrownBy(() -> response.toDomain(LocalDate.of(2026, 4, 3)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

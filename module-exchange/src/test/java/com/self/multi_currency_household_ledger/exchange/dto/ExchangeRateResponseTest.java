package com.self.multi_currency_household_ledger.exchange.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.self.multi_currency_household_ledger.exchange.domain.CurrencyCode;
import com.self.multi_currency_household_ledger.exchange.domain.ExchangeRate;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ExchangeRateResponseTest {

    @Test
    @DisplayName("ExchangeRate 엔티티로부터 응답 DTO를 매핑한다")
    void from_maps_entity_fields() {
        LocalDate date = LocalDate.of(2026, 4, 3);
        ExchangeRate rate = ExchangeRate.of(CurrencyCode.JPY, new BigDecimal("900.00"), date);

        ExchangeRateResponse response = ExchangeRateResponse.from(rate);

        assertThat(response.currencyCode()).isEqualTo(CurrencyCode.JPY);
        assertThat(response.currencyName()).isEqualTo("일본 엔");
        assertThat(response.dealBasRate()).isEqualByComparingTo(new BigDecimal("900.00"));
        assertThat(response.baseDate()).isEqualTo(date);
    }
}

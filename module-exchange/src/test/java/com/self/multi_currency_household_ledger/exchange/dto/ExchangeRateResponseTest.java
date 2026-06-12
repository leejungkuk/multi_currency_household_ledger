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
    @DisplayName("ExchangeRate 엔티티로부터 응답 DTO를 매핑하고, 기준일=요청일이면 stale=false")
    void from_maps_entity_fields() {
        LocalDate date = LocalDate.of(2026, 4, 3);
        ExchangeRate rate = ExchangeRate.of(CurrencyCode.JPY, new BigDecimal("900.00"), date);

        ExchangeRateResponse response = ExchangeRateResponse.from(rate, date);

        assertThat(response.currencyCode()).isEqualTo(CurrencyCode.JPY);
        assertThat(response.currencyName()).isEqualTo("일본 엔");
        assertThat(response.dealBasRate()).isEqualByComparingTo(new BigDecimal("900.00"));
        assertThat(response.baseDate()).isEqualTo(date);
        assertThat(response.stale()).isFalse();
    }

    @Test
    @DisplayName("기준일이 요청일과 다른 fallback 환율이면 stale=true")
    void from_marks_stale_when_base_date_differs_from_requested_date() {
        LocalDate baseDate = LocalDate.of(2026, 4, 3);
        ExchangeRate rate = ExchangeRate.of(CurrencyCode.JPY, new BigDecimal("900.00"), baseDate);

        ExchangeRateResponse response = ExchangeRateResponse.from(rate, LocalDate.of(2026, 4, 5));

        assertThat(response.stale()).isTrue();
    }
}

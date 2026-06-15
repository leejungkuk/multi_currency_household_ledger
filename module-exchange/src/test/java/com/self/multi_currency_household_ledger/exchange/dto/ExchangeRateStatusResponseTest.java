package com.self.multi_currency_household_ledger.exchange.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.self.multi_currency_household_ledger.exchange.domain.CurrencyCode;
import com.self.multi_currency_household_ledger.exchange.domain.ExchangeRate;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ExchangeRateStatusResponseTest {

    @Test
    @DisplayName("통화별 최신 환율 상태와 fetched_at 최댓값을 last_updated로 매핑한다")
    void from_maps_latest_rate_status_and_last_updated() {
        LocalDate date = LocalDate.of(2026, 4, 3);
        LocalDateTime usdFetchedAt = LocalDateTime.of(2026, 4, 3, 11, 5);
        LocalDateTime eurFetchedAt = LocalDateTime.of(2026, 4, 3, 11, 6);
        ExchangeRate usd = exchangeRate(CurrencyCode.USD, "1300.00", date, usdFetchedAt);
        ExchangeRate eur = exchangeRate(CurrencyCode.EUR, "1450.00", date, eurFetchedAt);

        ExchangeRateStatusResponse response = ExchangeRateStatusResponse.from(List.of(usd, eur));

        assertThat(response.rates()).hasSize(2);
        assertThat(response.rates().get(0).currencyCode()).isEqualTo(CurrencyCode.USD);
        assertThat(response.rates().get(0).baseDate()).isEqualTo(date);
        assertThat(response.rates().get(0).fetchedAt()).isEqualTo(usdFetchedAt);
        assertThat(response.lastUpdated()).isEqualTo(eurFetchedAt);
    }

    @Test
    @DisplayName("환율 상태가 없으면 빈 목록과 null last_updated를 반환한다")
    void from_returns_empty_status_when_rates_are_empty() {
        ExchangeRateStatusResponse response = ExchangeRateStatusResponse.from(List.of());

        assertThat(response.rates()).isEmpty();
        assertThat(response.lastUpdated()).isNull();
    }

    private static ExchangeRate exchangeRate(
            CurrencyCode currencyCode, String tts, LocalDate baseDate, LocalDateTime fetchedAt) {
        ExchangeRate rate = ExchangeRate.of(currencyCode, new BigDecimal(tts), baseDate);
        ReflectionTestUtils.setField(rate, "createdAt", fetchedAt);
        return rate;
    }
}

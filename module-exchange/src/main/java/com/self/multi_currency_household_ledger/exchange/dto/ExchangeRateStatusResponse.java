package com.self.multi_currency_household_ledger.exchange.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.self.multi_currency_household_ledger.exchange.domain.CurrencyCode;
import com.self.multi_currency_household_ledger.exchange.domain.ExchangeRate;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public record ExchangeRateStatusResponse(
        List<CurrencyRateStatus> rates, @JsonProperty("last_updated") LocalDateTime lastUpdated) {

    public static ExchangeRateStatusResponse from(List<ExchangeRate> exchangeRates) {
        List<CurrencyRateStatus> rates =
                exchangeRates.stream().map(CurrencyRateStatus::from).toList();
        LocalDateTime lastUpdated = rates.stream()
                .map(CurrencyRateStatus::fetchedAt)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);
        return new ExchangeRateStatusResponse(rates, lastUpdated);
    }

    public record CurrencyRateStatus(
            @JsonProperty("currency_code") CurrencyCode currencyCode,
            @JsonProperty("base_date") LocalDate baseDate,
            @JsonProperty("fetched_at") LocalDateTime fetchedAt) {

        private static CurrencyRateStatus from(ExchangeRate exchangeRate) {
            return new CurrencyRateStatus(
                    exchangeRate.getCurrencyCode(), exchangeRate.getBaseDate(), exchangeRate.getCreatedAt());
        }
    }
}

package com.self.multi_currency_household_ledger.exchange.dto;

import com.self.multi_currency_household_ledger.exchange.domain.CurrencyCode;
import com.self.multi_currency_household_ledger.exchange.domain.ExchangeRate;
import java.math.BigDecimal;
import java.time.LocalDate;

public record ExchangeRateResponse(
        CurrencyCode currencyCode,
        String currencyName,
        BigDecimal dealBasRate,
        LocalDate baseDate
) {

    public static ExchangeRateResponse from(ExchangeRate exchangeRate) {
        return new ExchangeRateResponse(
                exchangeRate.getCurrencyCode(),
                exchangeRate.getCurrencyCode().getDisplayName(),
                exchangeRate.getDealBasRate(),
                exchangeRate.getBaseDate()
        );
    }
}

package com.self.multi_currency_household_ledger.exchange.dto;

import com.self.multi_currency_household_ledger.exchange.domain.CurrencyCode;
import com.self.multi_currency_household_ledger.exchange.domain.ExchangeRate;
import java.math.BigDecimal;
import java.time.LocalDate;

public record ExchangeRateResponse(
        CurrencyCode currencyCode, String currencyName, BigDecimal dealBasRate, LocalDate baseDate, boolean stale) {

    /** stale = 기준일(baseDate)이 유효 요청일과 다른 fallback 환율 여부 — 프론트(woni_app) 계약 필드. */
    public static ExchangeRateResponse from(ExchangeRate exchangeRate, LocalDate requestedDate) {
        return new ExchangeRateResponse(
                exchangeRate.getCurrencyCode(),
                exchangeRate.getCurrencyCode().getDisplayName(),
                exchangeRate.getDealBasRate(),
                exchangeRate.getBaseDate(),
                !exchangeRate.getBaseDate().equals(requestedDate));
    }
}

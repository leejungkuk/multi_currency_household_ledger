package com.self.multi_currency_household_ledger.exchange.provider;

import com.self.multi_currency_household_ledger.exchange.domain.CurrencyCode;
import com.self.multi_currency_household_ledger.exchange.domain.ExchangeRate;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 수출입은행 API 응답 DTO
 */
public record ExchangeRateApiResponse(
        String curUnit,
        String curNm,
        BigDecimal dealBasR
) {

    public ExchangeRate toDomain(LocalDate baseDate) {
        CurrencyCode currencyCode = CurrencyCode.fromCode(curUnit);
        return ExchangeRate.of(currencyCode, dealBasR, baseDate);
    }
}

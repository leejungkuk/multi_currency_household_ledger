package com.self.multi_currency_household_ledger.ledger.domain;

import com.self.multi_currency_household_ledger.exchange.domain.CurrencyCode;
import java.math.BigDecimal;
import java.util.Map;

/** 한 달의 금액 세트(예산 통화·전체·몫). 미설정에는 없다. */
public record BudgetAmounts(
        CurrencyCode currency,
        BigDecimal total,
        Map<PaymentGroup, BigDecimal> paymentGroupAmounts,
        Map<Long, BigDecimal> categoryAmounts) {}

package com.self.multi_currency_household_ledger.ledger.domain;

import com.self.multi_currency_household_ledger.exchange.domain.CurrencyCode;
import java.math.BigDecimal;
import java.time.LocalDate;

/** 예산 실제 금액을 셀 거래 한 건. 날짜별로 미리 합치지 않고 행 단위로 받는다(요구사항 §3). */
public record BudgetTransaction(
        CurrencyCode currency,
        BigDecimal originalAmount,
        BigDecimal krwAmount,
        LocalDate transactionDate,
        long categoryId,
        long assetId) {}

package com.self.multi_currency_household_ledger.ledger.dto;

import java.math.BigDecimal;

public record LedgerMonthlySummaryResponse(BigDecimal income, BigDecimal expense, BigDecimal total) {

    public LedgerMonthlySummaryResponse(BigDecimal income, BigDecimal expense) {
        this(income, expense, income.subtract(expense));
    }
}

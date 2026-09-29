package com.self.multi_currency_household_ledger.ledger.dto;

import java.time.YearMonth;

/** remainingDaysIncludingToday 는 요청한 달이 이번 달일 때만 있다(예산이 없어도 준다). */
public record MonthlyBudgetResponse(
        int year,
        int month,
        int currentYear,
        int currentMonth,
        boolean editable,
        Integer remainingDaysIncludingToday,
        boolean hasAnyBudget,
        BudgetAxisResponse expense,
        BudgetAxisResponse income) {

    public static MonthlyBudgetResponse of(
            YearMonth month,
            YearMonth current,
            boolean editable,
            Integer remainingDaysIncludingToday,
            boolean hasAnyBudget,
            BudgetAxisResponse expense,
            BudgetAxisResponse income) {
        return new MonthlyBudgetResponse(
                month.getYear(),
                month.getMonthValue(),
                current.getYear(),
                current.getMonthValue(),
                editable,
                remainingDaysIncludingToday,
                hasAnyBudget,
                expense,
                income);
    }
}

package com.self.multi_currency_household_ledger.ledger.domain;

import java.time.YearMonth;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** 한 축의 행들로 정한 달 M 의 출처와 적용 금액 세트. amounts 는 금액 세트일 때만 있다. */
public record BudgetResolution(BudgetSource source, BudgetAmounts amounts) {

    public static BudgetResolution resolve(List<Budget> axisRows, YearMonth month) {
        Optional<Budget> monthRow = axisRows.stream()
                .filter(row ->
                        row.getKind() == BudgetKind.MONTH && row.yearMonth().equals(month))
                .findFirst();
        if (monthRow.isPresent()) {
            return of(monthRow.get(), BudgetSource.MONTH_VALUE, BudgetSource.MONTH_OFF);
        }
        // 적용 시작 달이 M 이하인 기본값 중 가장 늦은 것. M 보다 늦은 기본값은 M 을 해석하지 않는다.
        Optional<Budget> defaultRow = axisRows.stream()
                .filter(row ->
                        row.getKind() == BudgetKind.DEFAULT && !row.yearMonth().isAfter(month))
                .max(Comparator.comparing(Budget::yearMonth));
        return defaultRow
                .map(row -> of(row, BudgetSource.DEFAULT, BudgetSource.DEFAULT_OFF))
                .orElseGet(() -> new BudgetResolution(BudgetSource.NOT_SET, null));
    }

    private static BudgetResolution of(Budget row, BudgetSource valued, BudgetSource off) {
        return row.amounts()
                .map(amounts -> new BudgetResolution(valued, amounts))
                .orElseGet(() -> new BudgetResolution(off, null));
    }
}

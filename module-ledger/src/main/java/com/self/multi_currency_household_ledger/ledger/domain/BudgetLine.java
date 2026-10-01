package com.self.multi_currency_household_ledger.ledger.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 예산 한 줄(전체·결제수단 그룹·카테고리). 판정은 scale 10 값으로, 표시값은 통화 자릿수에서 내림(넘은 금액만 올림)한다. 그래서 NEAR_LIMIT 인데
 * remainingAmount 가 0 일 수 있다.
 */
public record BudgetLine(
        BigDecimal budgetAmount,
        BigDecimal actualAmount,
        BudgetStatus status,
        Integer percent,
        BigDecimal remainingAmount,
        BigDecimal overAmount) {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal NEAR_LIMIT_PERCENT = BigDecimal.valueOf(80);

    /** 몫이 없는 결제수단 그룹 — 실제 금액만 있다. */
    static BudgetLine actualOnly(BigDecimal actual, int fractionDigits) {
        return new BudgetLine(null, floor(actual, fractionDigits), null, null, null, null);
    }

    static BudgetLine of(BigDecimal budget, BigDecimal actual, int fractionDigits) {
        BigDecimal budgetAmount = floor(budget, fractionDigits);
        BigDecimal actualAmount = floor(actual, fractionDigits);
        int comparison = actual.compareTo(budget);
        if (comparison > 0) {
            BigDecimal over = actual.subtract(budget).setScale(fractionDigits, RoundingMode.CEILING);
            return new BudgetLine(budgetAmount, actualAmount, BudgetStatus.EXCEEDED, null, null, over);
        }
        BigDecimal remaining = floor(budget.subtract(actual), fractionDigits);
        Integer percent = budget.signum() == 0
                ? null
                : actual.multiply(HUNDRED).divide(budget, 0, RoundingMode.FLOOR).intValueExact();
        return new BudgetLine(budgetAmount, actualAmount, status(budget, actual, comparison), percent, remaining, null);
    }

    private static BudgetStatus status(BigDecimal budget, BigDecimal actual, int comparison) {
        if (actual.signum() == 0) {
            return BudgetStatus.NONE;
        }
        if (comparison == 0) {
            return BudgetStatus.REACHED;
        }
        // 80% 이상 판정은 나누지 않고 actual × 100 ≥ budget × 80 으로 한다(나눗셈 반올림이 경계를 흐리지 않게).
        if (actual.multiply(HUNDRED).compareTo(budget.multiply(NEAR_LIMIT_PERCENT)) >= 0) {
            return BudgetStatus.NEAR_LIMIT;
        }
        return BudgetStatus.IN_PROGRESS;
    }

    static BigDecimal floor(BigDecimal value, int fractionDigits) {
        return value.setScale(fractionDigits, RoundingMode.FLOOR);
    }
}

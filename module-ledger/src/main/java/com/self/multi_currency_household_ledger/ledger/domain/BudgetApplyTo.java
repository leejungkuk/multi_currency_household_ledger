package com.self.multi_currency_household_ledger.ledger.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 쓰기 범위. 이 달만 = (MONTH, M), 이 달부터 계속 = (DEFAULT, M). */
@Getter
@RequiredArgsConstructor
public enum BudgetApplyTo {
    THIS_MONTH(BudgetKind.MONTH),
    FROM_THIS_MONTH(BudgetKind.DEFAULT);

    private final BudgetKind kind;
}

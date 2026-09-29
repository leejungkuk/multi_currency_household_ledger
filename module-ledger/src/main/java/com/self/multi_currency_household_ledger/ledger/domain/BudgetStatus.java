package com.self.multi_currency_household_ledger.ledger.domain;

/** 지출·수입이 함께 쓴다. 수입은 NEAR_LIMIT 을 쓰지 않는다. */
public enum BudgetStatus {
    NOT_SET,
    OFF,
    NONE,
    IN_PROGRESS,
    NEAR_LIMIT,
    REACHED,
    EXCEEDED
}

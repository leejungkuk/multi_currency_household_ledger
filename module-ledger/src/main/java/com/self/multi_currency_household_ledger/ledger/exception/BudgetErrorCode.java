package com.self.multi_currency_household_ledger.ledger.exception;

import com.self.multi_currency_household_ledger.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum BudgetErrorCode implements ErrorCode {
    BUDGET_MONTH_OUT_OF_RANGE(
            "BUDGET_MONTH_OUT_OF_RANGE", "예산은 2000년 1월부터 이번 달 12개월 뒤까지만 바꿀 수 있습니다.", HttpStatus.BAD_REQUEST),
    BUDGET_INVALID_AMOUNT(
            "BUDGET_INVALID_AMOUNT", "예산 금액은 0 이상 99,999,999 이하, 통화 자릿수 이내여야 합니다.", HttpStatus.BAD_REQUEST),
    BUDGET_TOTAL_REQUIRED("BUDGET_TOTAL_REQUIRED", "전체 예산 금액이 필요합니다.", HttpStatus.BAD_REQUEST),
    BUDGET_INVALID_ALLOCATION("BUDGET_INVALID_ALLOCATION", "예산 몫 구성이 올바르지 않습니다.", HttpStatus.BAD_REQUEST);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;
}

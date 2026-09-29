package com.self.multi_currency_household_ledger.ledger.exception;

import com.self.multi_currency_household_ledger.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum BudgetErrorCode implements ErrorCode {
    BUDGET_PAST_MONTH("BUDGET_PAST_MONTH", "지난 달 예산은 바꿀 수 없습니다.", HttpStatus.BAD_REQUEST),
    BUDGET_MONTH_TOO_FAR("BUDGET_MONTH_TOO_FAR", "다음 달까지의 예산만 바꿀 수 있습니다.", HttpStatus.BAD_REQUEST),
    BUDGET_INVALID_AMOUNT(
            "BUDGET_INVALID_AMOUNT", "예산 금액은 0 이상 99,999,999 이하, 통화 자릿수 이내여야 합니다.", HttpStatus.BAD_REQUEST),
    BUDGET_TOTAL_REQUIRED("BUDGET_TOTAL_REQUIRED", "전체 예산 금액이 필요합니다.", HttpStatus.BAD_REQUEST),
    BUDGET_INVALID_ALLOCATION("BUDGET_INVALID_ALLOCATION", "예산 몫 구성이 올바르지 않습니다.", HttpStatus.BAD_REQUEST);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;
}

package com.self.multi_currency_household_ledger.ledger.exception;

import com.self.multi_currency_household_ledger.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum LedgerErrorCode implements ErrorCode {
    CATEGORY_NOT_FOUND("CATEGORY_NOT_FOUND", "카테고리를 찾을 수 없습니다.", HttpStatus.NOT_FOUND),
    ASSET_NOT_FOUND("ASSET_NOT_FOUND", "자산을 찾을 수 없습니다.", HttpStatus.NOT_FOUND),
    LEDGER_ENTRY_NOT_FOUND("LEDGER_ENTRY_NOT_FOUND", "가계부 내역을 찾을 수 없습니다.", HttpStatus.NOT_FOUND),
    LEDGER_IMPORT_CONFLICT("LEDGER_IMPORT_CONFLICT", "이미 import된 거래와 요청 내용이 충돌합니다.", HttpStatus.CONFLICT),
    INVALID_RESTORE_CURSOR("INVALID_RESTORE_CURSOR", "복원 커서가 올바르지 않습니다.", HttpStatus.BAD_REQUEST),
    INVALID_CHANGES_CURSOR("INVALID_CHANGES_CURSOR", "변경분 커서가 올바르지 않습니다.", HttpStatus.BAD_REQUEST),
    INVALID_AMOUNT("INVALID_AMOUNT", "금액은 0보다 크고 99,999,999 이하여야 합니다.", HttpStatus.BAD_REQUEST),
    INVALID_FUTURE_DATE("INVALID_FUTURE_DATE", "외화 거래는 미래 날짜를 입력할 수 없습니다.", HttpStatus.BAD_REQUEST);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;
}

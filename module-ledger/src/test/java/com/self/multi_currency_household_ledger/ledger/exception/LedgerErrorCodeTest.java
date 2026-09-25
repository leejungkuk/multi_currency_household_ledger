package com.self.multi_currency_household_ledger.ledger.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class LedgerErrorCodeTest {

    @Test
    @DisplayName("가계부 도메인의 모든 에러 코드가 올바른 HTTP 상태와 메시지를 가지는지 검증한다")
    void verify_ledger_error_codes() {
        // 모든 가계부 에러 코드의 상태, 코드, 메시지가 명세와 일치하는지 확인한다
        assertErrorCode(
                LedgerErrorCode.CATEGORY_NOT_FOUND, HttpStatus.NOT_FOUND, "CATEGORY_NOT_FOUND", "카테고리를 찾을 수 없습니다.");
        assertErrorCode(LedgerErrorCode.ASSET_NOT_FOUND, HttpStatus.NOT_FOUND, "ASSET_NOT_FOUND", "자산을 찾을 수 없습니다.");
        assertErrorCode(
                LedgerErrorCode.LEDGER_ENTRY_NOT_FOUND,
                HttpStatus.NOT_FOUND,
                "LEDGER_ENTRY_NOT_FOUND",
                "가계부 내역을 찾을 수 없습니다.");
        assertErrorCode(
                LedgerErrorCode.INVALID_AMOUNT,
                HttpStatus.BAD_REQUEST,
                "INVALID_AMOUNT",
                "금액은 0보다 크고 99,999,999 이하여야 합니다.");
        assertErrorCode(
                LedgerErrorCode.INVALID_FUTURE_DATE,
                HttpStatus.BAD_REQUEST,
                "INVALID_FUTURE_DATE",
                "외화 거래는 미래 날짜를 입력할 수 없습니다.");
    }

    private void assertErrorCode(
            LedgerErrorCode error, HttpStatus expectedStatus, String expectedCode, String expectedMessage) {
        assertThat(error.getHttpStatus()).isEqualTo(expectedStatus);
        assertThat(error.getCode()).isEqualTo(expectedCode);
        assertThat(error.getMessage()).isEqualTo(expectedMessage);
    }
}

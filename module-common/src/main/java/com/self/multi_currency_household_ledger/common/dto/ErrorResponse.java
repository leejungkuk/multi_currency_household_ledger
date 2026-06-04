package com.self.multi_currency_household_ledger.common.dto;

import java.time.LocalDateTime;

/** 에러 응답 전용 봉투. 성공 응답은 {@link ApiResponse} 참조. */
public record ErrorResponse(String code, String message, LocalDateTime timestamp) {

    public static ErrorResponse of(String code, String message) {
        return new ErrorResponse(code, message, LocalDateTime.now());
    }
}

package com.self.multi_currency_household_ledger.common.dto;

import java.time.LocalDateTime;

/** 성공 응답 전용 봉투. 에러 응답은 {@link ErrorResponse} 참조. */
public record ApiResponse<T>(T data, LocalDateTime timestamp) {

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(data, LocalDateTime.now());
    }
}

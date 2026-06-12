package com.self.multi_currency_household_ledger.common.dto;

import java.time.LocalDateTime;
import java.time.ZoneId;

/** 성공 응답 전용 봉투. 에러 응답은 {@link ErrorResponse} 참조. */
public record ApiResponse<T>(boolean success, String code, T data, String message, LocalDateTime timestamp) {

    public static <T> ApiResponse<T> success(T data) {
        // 서버 기본 TZ 와 무관하게 KST 고정 — 프론트 계약(Jackson Asia/Seoul)과 일치
        return new ApiResponse<>(true, null, data, null, LocalDateTime.now(ZoneId.of("Asia/Seoul")));
    }
}

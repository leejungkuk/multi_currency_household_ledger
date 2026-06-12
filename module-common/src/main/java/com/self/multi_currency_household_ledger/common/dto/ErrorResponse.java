package com.self.multi_currency_household_ledger.common.dto;

import java.time.LocalDateTime;
import java.time.ZoneId;

/** 에러 응답 전용 봉투. 성공 응답은 {@link ApiResponse} 참조. */
public record ErrorResponse(boolean success, String code, String message, LocalDateTime timestamp) {

    public static ErrorResponse of(String code, String message) {
        // 서버 기본 TZ 와 무관하게 KST 고정 — 프론트 계약(Jackson Asia/Seoul)과 일치
        return new ErrorResponse(false, code, message, LocalDateTime.now(ZoneId.of("Asia/Seoul")));
    }
}

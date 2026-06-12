package com.self.multi_currency_household_ledger.exchange.exception;

import com.self.multi_currency_household_ledger.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ExchangeErrorCode implements ErrorCode {
    EXCHANGE_RATE_NOT_FOUND("EXCHANGE_RATE_NOT_FOUND", "환율 정보가 존재하지 않습니다.", HttpStatus.NOT_FOUND),
    EXCHANGE_API_ERROR("EXCHANGE_API_ERROR", "환율 정보를 가져오는데 실패했습니다.", HttpStatus.INTERNAL_SERVER_ERROR),
    EXCHANGE_API_AUTH_ERROR("EXCHANGE_API_AUTH_ERROR", "수출입은행 API 인증키 오류", HttpStatus.INTERNAL_SERVER_ERROR),
    EXCHANGE_API_LIMIT_EXCEEDED("EXCHANGE_API_LIMIT_EXCEEDED", "수출입은행 API 일일 호출 한도 초과", HttpStatus.SERVICE_UNAVAILABLE),
    INVALID_DATE("INVALID_DATE", "미래 날짜의 환율은 조회할 수 없습니다.", HttpStatus.BAD_REQUEST),
    UNSUPPORTED_CURRENCY("UNSUPPORTED_CURRENCY", "지원하지 않는 통화 코드입니다.", HttpStatus.BAD_REQUEST);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;
}

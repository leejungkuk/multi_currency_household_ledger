package com.self.multi_currency_household_ledger.common.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public class BusinessException extends RuntimeException {

    private final String code;
    private final HttpStatus httpStatus;

    /**
     * 임의 코드/메시지 기반 fallback 생성자 — 상태코드는 항상 400으로 고정된다. 새 도메인 오류는 {@link ErrorCode}를 구현한 enum으로 의미에
     * 맞는 {@link HttpStatus}를 선언하고 {@link #BusinessException(ErrorCode)}를 써야 한다. 외부 모듈의 무분별한 사용을 막기
     * 위해 package-private로 제한한다(common 모듈 내부 fallback·테스트 전용).
     */
    BusinessException(String code, String message) {
        super(message);
        this.code = code;
        this.httpStatus = HttpStatus.BAD_REQUEST;
    }

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.code = errorCode.getCode();
        this.httpStatus = errorCode.getHttpStatus();
    }
}

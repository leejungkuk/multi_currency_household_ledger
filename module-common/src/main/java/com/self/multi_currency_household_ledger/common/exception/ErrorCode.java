package com.self.multi_currency_household_ledger.common.exception;

import org.springframework.http.HttpStatus;

public interface ErrorCode {
    ErrorCode UNAUTHORIZED = Common.UNAUTHORIZED;
    ErrorCode FORBIDDEN = Common.FORBIDDEN;

    String getCode();

    String getMessage();

    HttpStatus getHttpStatus();

    enum Common implements ErrorCode {
        UNAUTHORIZED("UNAUTHORIZED", "인증이 필요합니다.", HttpStatus.UNAUTHORIZED),
        FORBIDDEN("FORBIDDEN", "접근 권한이 없습니다.", HttpStatus.FORBIDDEN);

        private final String code;
        private final String message;
        private final HttpStatus httpStatus;

        Common(String code, String message, HttpStatus httpStatus) {
            this.code = code;
            this.message = message;
            this.httpStatus = httpStatus;
        }

        @Override
        public String getCode() {
            return code;
        }

        @Override
        public String getMessage() {
            return message;
        }

        @Override
        public HttpStatus getHttpStatus() {
            return httpStatus;
        }
    }
}

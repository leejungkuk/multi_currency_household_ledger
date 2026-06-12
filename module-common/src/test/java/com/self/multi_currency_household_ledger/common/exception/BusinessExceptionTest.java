package com.self.multi_currency_household_ledger.common.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class BusinessExceptionTest {

    @Test
    void 문자열_생성자로_코드와_메시지를_담는다() {
        BusinessException ex = new BusinessException("TEST_CODE", "테스트 메시지");

        assertThat(ex.getCode()).isEqualTo("TEST_CODE");
        assertThat(ex.getMessage()).isEqualTo("테스트 메시지");
        assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void ErrorCode_생성자로_코드와_메시지와_httpStatus를_담는다() {
        ErrorCode errorCode = new ErrorCode() {
            @Override
            public String getCode() {
                return "TEST_CODE";
            }

            @Override
            public String getMessage() {
                return "테스트 메시지";
            }

            @Override
            public HttpStatus getHttpStatus() {
                return HttpStatus.NOT_FOUND;
            }
        };

        BusinessException ex = new BusinessException(errorCode);

        assertThat(ex.getCode()).isEqualTo("TEST_CODE");
        assertThat(ex.getMessage()).isEqualTo("테스트 메시지");
        assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}

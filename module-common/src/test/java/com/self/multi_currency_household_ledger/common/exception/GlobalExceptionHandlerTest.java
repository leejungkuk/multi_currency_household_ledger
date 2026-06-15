package com.self.multi_currency_household_ledger.common.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.self.multi_currency_household_ledger.common.dto.ErrorResponse;
import jakarta.validation.ConstraintViolationException;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("BusinessException(문자열)은 기본 400 + ErrorResponse 봉투로 변환된다")
    void handleBusinessException_string_constructor_returns_400() {
        ResponseEntity<ErrorResponse> response =
                handler.handleBusinessException(new BusinessException("INVALID_DATE", "미래 날짜는 조회할 수 없습니다."));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ErrorResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.code()).isEqualTo("INVALID_DATE");
        assertThat(body.message()).isEqualTo("미래 날짜는 조회할 수 없습니다.");
    }

    @Test
    @DisplayName("BusinessException(ErrorCode)은 ErrorCode의 httpStatus를 그대로 사용한다")
    void handleBusinessException_errorcode_constructor_uses_declared_status() {
        ErrorCode notFound = new ErrorCode() {
            @Override
            public String getCode() {
                return "NOT_FOUND";
            }

            @Override
            public String getMessage() {
                return "리소스 없음";
            }

            @Override
            public HttpStatus getHttpStatus() {
                return HttpStatus.NOT_FOUND;
            }
        };

        ResponseEntity<ErrorResponse> response = handler.handleBusinessException(new BusinessException(notFound));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("NOT_FOUND");
    }

    @Test
    @DisplayName("예상치 못한 예외는 HTTP 500 + INTERNAL_ERROR 봉투로 변환된다")
    void handleException_returns_500_envelope() {
        ResponseEntity<ErrorResponse> response = handler.handleException(new RuntimeException("boom"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        ErrorResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.code()).isEqualTo("INTERNAL_ERROR");
    }

    @Test
    @DisplayName("ConstraintViolationException은 400 + VALIDATION_ERROR 봉투로 변환된다")
    void handleConstraintViolationException_returns_400_validation_error() {
        ResponseEntity<ErrorResponse> response =
                handler.handleConstraintViolationException(new ConstraintViolationException(Set.of()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ErrorResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.code()).isEqualTo("VALIDATION_ERROR");
    }
}

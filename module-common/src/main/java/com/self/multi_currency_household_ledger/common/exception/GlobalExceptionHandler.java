package com.self.multi_currency_household_ledger.common.exception;

import com.self.multi_currency_household_ledger.common.dto.ErrorResponse;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusinessException(BusinessException e) {
        log.warn("Business exception: code={}, message={}", e.getCode(), e.getMessage());
        return ResponseEntity.status(e.getHttpStatus()).body(ErrorResponse.of(e.getCode(), e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationException(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .reduce((a, b) -> a + ", " + b)
                .orElse("Validation failed");
        log.warn("Validation exception: {}", message);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse.of("VALIDATION_ERROR", message));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolationException(ConstraintViolationException e) {
        String message = e.getConstraintViolations().stream()
                .map(violation -> violation.getPropertyPath() + ": " + violation.getMessage())
                .reduce((a, b) -> a + ", " + b)
                .orElse("Validation failed");
        log.warn("Constraint violation exception: {}", message);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse.of("VALIDATION_ERROR", message));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatchException(MethodArgumentTypeMismatchException e) {
        String value = e.getValue() != null ? e.getValue().toString() : "null";
        String message = String.format("파라미터 '%s'의 값 '%s'이 올바르지 않습니다.", e.getName(), value);
        log.warn("Type mismatch exception: {}", message);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse.of("INVALID_PARAMETER", message));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParameterException(MissingServletRequestParameterException e) {
        String message = String.format("필수 파라미터 '%s'이 누락되었습니다.", e.getParameterName());
        log.warn("Missing parameter exception: {}", message);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse.of("INVALID_PARAMETER", message));
    }

    /** 낙관적 락 충돌은 클라이언트가 최신 상태를 다시 읽고 재시도하면 해소되므로 500 이 아니라 409 다. */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> handleOptimisticLockingFailure(OptimisticLockingFailureException e) {
        ErrorCode errorCode = ErrorCode.Common.CONCURRENT_MODIFICATION;
        log.warn("Optimistic locking failure: {}", e.getMessage());
        return ResponseEntity.status(errorCode.getHttpStatus())
                .body(ErrorResponse.of(errorCode.getCode(), errorCode.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleException(Exception e) {
        log.error("Unexpected exception", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of("INTERNAL_ERROR", "서버 내부 오류가 발생했습니다."));
    }
}

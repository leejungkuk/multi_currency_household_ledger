package com.self.multi_currency_household_ledger.common.exception;

import com.self.multi_currency_household_ledger.common.dto.ErrorResponse;
import jakarta.validation.ConstraintViolationException;
import java.util.regex.Pattern;
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

    /**
     * 로그 한 줄을 쪼갤 수 있는 문자 전부. {@code \p{Cc}}(유니코드 Cc)를 쓰는 이유는 {@code \p{Cntrl}} 이 Java 에서
     * US-ASCII 범위만 뜻해 C1 제어문자(U+0085 NEL 등)를 놓치기 때문이다(실측). Cc 는 한글·이모지를 건드리지 않는다.
     */
    private static final Pattern CONTROL_CHARACTERS = Pattern.compile("[\\p{Cc}\\p{Zl}\\p{Zp}]");

    private static final int MAX_ECHOED_VALUE_LENGTH = 100;

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
        String value = e.getValue() != null ? sanitize(e.getValue().toString()) : "null";
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

    /**
     * 타입 불일치 메시지는 다른 핸들러와 달리 <b>사용자가 보낸 값 자체</b>를 싣는다. 그 메시지는 응답 본문이자 로그 한 줄이라, 개행이 그대로
     * 들어가면 로그 한 줄이 여러 줄로 쪼개져 없는 사건을 지어낼 수 있다(Loki 는 줄 단위로 수집한다). 경로·쿼리 값에는 길이 제한이 없으므로
     * 길이도 함께 자른다.
     */
    private static String sanitize(String value) {
        String singleLine = CONTROL_CHARACTERS.matcher(value).replaceAll("");
        if (singleLine.length() <= MAX_ECHOED_VALUE_LENGTH) {
            return singleLine;
        }
        // 길이는 char 단위라 상한이 서로게이트 쌍 한가운데 떨어질 수 있다. 그대로 자르면 짝 잃은 서로게이트가 남는다.
        int end = Character.isHighSurrogate(singleLine.charAt(MAX_ECHOED_VALUE_LENGTH - 1))
                ? MAX_ECHOED_VALUE_LENGTH - 1
                : MAX_ECHOED_VALUE_LENGTH;
        return singleLine.substring(0, end) + "...";
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleException(Exception e) {
        log.error("Unexpected exception", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of("INTERNAL_ERROR", "서버 내부 오류가 발생했습니다."));
    }
}

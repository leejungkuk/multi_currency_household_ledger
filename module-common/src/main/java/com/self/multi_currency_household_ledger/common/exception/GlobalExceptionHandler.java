package com.self.multi_currency_household_ledger.common.exception;

import com.self.multi_currency_household_ledger.common.dto.ErrorResponse;
import jakarta.validation.ConstraintViolationException;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
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

    /**
     * 본문을 못 읽는 것은 클라이언트 잘못이므로 400 이다. 이건 새 계약이 아니라 Spring 기본값 복원이다 —
     * {@code DefaultHandlerExceptionResolver} 가 원래 400 으로 매핑하는데, 아래 캐치올이 먼저 가로채 500 이 되고 있었다.
     * 그 부작용으로 요청 하나가 ERROR 스택트레이스 한 덩어리를 만들어 로그와 5xx 대시보드를 오염시켰다.
     * 엣지에서 본문이 잘린 요청(Caddy 의 2MB 상한)도 이 경로로 들어온다. (현 iOS 클라이언트는 status 로 분기하지 않아
     * 재시도 거동은 전후 같다 — 실질 이득은 대시보드·로그 쪽이고, 사용자에게는 문구가 정확해진다.)
     *
     * <p>파서 메시지는 응답에 싣지 않고 로그에만 남긴다 — 페이로드 조각이 섞일 수 있어 그대로 되돌려주면 공격자가 보낸 내용을 서버가
     * 반사하는 셈이 된다. 로그로 갈 때도 {@link #sanitize} 를 거쳐 한 줄을 넘지 못하게 한다.
     *
     * <p>원인 타입을 따로 찍는 이유는 이 예외가 클라이언트 잘못만 담고 있지 않기 때문이다. Jackson 의
     * {@code InvalidDefinitionException}(DTO 에 Creator 가 없는 등 <b>서버 버그</b>)도 같은 예외로 감싸여 오는데,
     * 잘린 메시지만 남기면 그게 파싱 실패에 묻힌다. 타입은 서버가 정한 값이라 그대로 찍어도 안전하다.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleMessageNotReadable(HttpMessageNotReadableException e) {
        log.warn(
                "Malformed request body: cause={}, detail={}",
                e.getMostSpecificCause().getClass().getSimpleName(),
                sanitize(String.valueOf(e.getMessage())));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of("MALFORMED_REQUEST", "요청 본문을 읽을 수 없습니다."));
    }

    /** 낙관적 락 충돌은 클라이언트가 최신 상태를 다시 읽고 재시도하면 해소되므로 500 이 아니라 409 다. */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> handleOptimisticLockingFailure(OptimisticLockingFailureException e) {
        ErrorCode errorCode = ErrorCode.Common.CONCURRENT_MODIFICATION;
        log.warn("Optimistic locking failure: {}", e.getMessage());
        return ResponseEntity.status(errorCode.getHttpStatus())
                .body(ErrorResponse.of(errorCode.getCode(), errorCode.getMessage()));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrityViolation(DataIntegrityViolationException e) {
        if (DatabaseConstraints.isLedgerEntryMemberForeignKeyViolation(e)) {
            ErrorCode errorCode = ErrorCode.Common.UNAUTHORIZED;
            return ResponseEntity.status(errorCode.getHttpStatus())
                    .body(ErrorResponse.of(errorCode.getCode(), errorCode.getMessage()));
        }
        return handleException(e);
    }

    /**
     * 클라이언트가 보낸 문자열을 기록·반환하기 전에 한 줄로 접고 길이를 자른다. 개행이 남으면 로그 한 줄이 여러 줄로 쪼개져 없는 사건을
     * 지어낼 수 있고(Loki 는 줄 단위로 수집한다), 경로·쿼리·본문에는 길이 제한이 없어 요청 하나가 로그를 부풀린다.
     *
     * <p>호출자는 둘이고 노출 범위가 다르다. 타입 불일치는 이 결과를 <b>응답 본문에도</b> 실으므로 상한이 곧 에코 상한이다.
     * 본문 파싱 실패는 로그에만 쓰므로 상한이 진단 깊이를 정한다 — 그쪽은 원인 타입을 따로 찍어 보완한다. 상한을 조정할 때 두 성격을
     * 같이 움직인다는 점을 염두에 둘 것.
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

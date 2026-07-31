package com.self.multi_currency_household_ledger.common.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.self.multi_currency_household_ledger.common.dto.ErrorResponse;
import jakarta.validation.ConstraintViolationException;
import java.lang.reflect.Method;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.ExceptionHandlerMethodResolver;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

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

    @Test
    @DisplayName("필수 요청 파라미터 누락은 400 + INVALID_PARAMETER 봉투로 변환된다")
    void handleMissingParameterException_returns_400_invalid_parameter() {
        ResponseEntity<ErrorResponse> response = handler.handleMissingParameterException(
                new MissingServletRequestParameterException("from", "LocalDate"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ErrorResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.code()).isEqualTo("INVALID_PARAMETER");
        assertThat(body.message()).contains("from");
    }

    @Test
    @DisplayName("필수 요청 파라미터 누락은 캐치올보다 구체적인 핸들러로 디스패치된다")
    void missingParameterException_resolves_specific_handler() {
        var resolver = new ExceptionHandlerMethodResolver(GlobalExceptionHandler.class);

        Method resolved = resolver.resolveMethod(new MissingServletRequestParameterException("from", "LocalDate"));

        assertThat(resolved).isNotNull();
        assertThat(resolved.getName()).isEqualTo("handleMissingParameterException");
    }

    @Test
    @DisplayName("낙관적 락 충돌은 500이 아니라 409 + CONCURRENT_MODIFICATION 봉투로 변환된다")
    void handleOptimisticLockingFailure_returns_409_envelope() {
        ResponseEntity<ErrorResponse> response =
                handler.handleOptimisticLockingFailure(new ObjectOptimisticLockingFailureException("LedgerEntry", 1L));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        ErrorResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.code()).isEqualTo("CONCURRENT_MODIFICATION");
        assertThat(body.message()).isNotBlank();
    }

    /**
     * 타입 불일치는 핸들러 중 유일하게 <b>사용자가 보낸 값 자체</b>를 메시지에 싣는다(다른 핸들러는 파라미터명·검증 메시지처럼 서버가 정한
     * 문자열만 쓴다). 그 메시지는 응답 본문이자 로그 한 줄이라, 개행이 그대로 들어가면 Loki 에 가짜 로그 라인을 심을 수 있다.
     */
    @Test
    @DisplayName("타입 불일치 메시지는 사용자 값의 개행·제어문자를 지워 로그 한 줄을 위조할 수 없게 한다")
    void handleTypeMismatchException_strips_control_characters_from_user_value() {
        String forgedLogLine = "USD\r\n2026-07-31 00:00:00 ERROR [woni] 관리자 계정이 삭제되었습니다";

        ResponseEntity<ErrorResponse> response = handler.handleTypeMismatchException(
                new MethodArgumentTypeMismatchException(forgedLogLine, String.class, "currencyCode", null, null));

        ErrorResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.message())
                .as("개행이 남으면 로그 한 줄이 두 줄이 되어 없는 사건을 지어낼 수 있다")
                .doesNotContain("\r")
                .doesNotContain("\n")
                .contains("currencyCode");
    }

    /**
     * {@code \p{Cntrl}} 은 Java 에서 US-ASCII 범위(0x00-0x1F,0x7F)만 뜻해 C1 제어문자를 놓친다(실측). 그중 U+0085 NEL 은
     * 유니코드가 줄바꿈으로 규정한 문자라, 로그를 유니코드 기준으로 다루는 뷰어에서는 줄이 갈린다.
     */
    @Test
    @DisplayName("ASCII 밖 제어문자(U+0085 NEL)와 줄·문단 구분자도 함께 제거된다")
    void handleTypeMismatchException_strips_non_ascii_control_characters() {
        String nextLine = String.valueOf((char) 0x0085); // C1 NEL
        String lineSeparator = String.valueOf((char) 0x2028); // Zl
        String paragraphSeparator = String.valueOf((char) 0x2029); // Zp
        String forged = "USD" + nextLine + "가짜 로그" + lineSeparator + "또 한 줄" + paragraphSeparator + "끝";

        ResponseEntity<ErrorResponse> response = handler.handleTypeMismatchException(
                new MethodArgumentTypeMismatchException(forged, String.class, "currencyCode", null, null));

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message())
                .doesNotContain(nextLine)
                .doesNotContain(lineSeparator)
                .doesNotContain(paragraphSeparator);
    }

    @Test
    @DisplayName("정상 입력인 한글·이모지는 훼손하지 않는다")
    void handleTypeMismatchException_preserves_ordinary_text() {
        ResponseEntity<ErrorResponse> response = handler.handleTypeMismatchException(
                new MethodArgumentTypeMismatchException("원화😀", String.class, "currencyCode", null, null));

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).contains("원화😀");
    }

    /** 길이는 char 단위라 상한이 서로게이트 쌍 한가운데 떨어질 수 있다. 그대로 자르면 짝 잃은 서로게이트가 남는다. */
    @Test
    @DisplayName("값을 자를 때 서로게이트 쌍을 쪼개지 않는다")
    void handleTypeMismatchException_truncates_on_a_character_boundary() {
        String emojiRun = "a" + "😀".repeat(200);

        ResponseEntity<ErrorResponse> response = handler.handleTypeMismatchException(
                new MethodArgumentTypeMismatchException(emojiRun, String.class, "memo", null, null));

        assertThat(response.getBody()).isNotNull();
        // 짝이 맞는 쌍은 codePoints() 가 하나의 보충문자로 합치므로, 서로게이트 범위 값이 남았다면 그것은 짝을 잃은 것이다.
        assertThat(response.getBody().message().codePoints())
                .as("짝 잃은 서로게이트가 남으면 로그·응답이 깨진 문자로 나간다")
                .noneMatch(codePoint -> codePoint >= Character.MIN_SURROGATE && codePoint <= Character.MAX_SURROGATE);
    }

    @Test
    @DisplayName("타입 불일치 메시지는 과도하게 긴 사용자 값을 잘라낸다")
    void handleTypeMismatchException_truncates_oversized_user_value() {
        ResponseEntity<ErrorResponse> response = handler.handleTypeMismatchException(
                new MethodArgumentTypeMismatchException("x".repeat(10_000), String.class, "date", null, null));

        ErrorResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.message())
                .as("경로·쿼리 값은 길이 제한이 없어 그대로 실으면 로그와 응답이 요청 하나로 부풀어 오른다")
                .hasSizeLessThan(500);
    }

    @Test
    @DisplayName("낙관적 락 충돌은 캐치올(500)이 아니라 전용 핸들러로 디스패치된다")
    void optimisticLockingFailure_resolves_specific_handler() {
        var resolver = new ExceptionHandlerMethodResolver(GlobalExceptionHandler.class);

        Method resolved = resolver.resolveMethod(new ObjectOptimisticLockingFailureException("LedgerEntry", 1L));

        assertThat(resolved).isNotNull();
        assertThat(resolved.getName()).isEqualTo("handleOptimisticLockingFailure");
    }
}

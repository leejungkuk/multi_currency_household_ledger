package com.self.multi_currency_household_ledger.config;

import static java.util.stream.Collectors.toUnmodifiableSet;

import com.self.multi_currency_household_ledger.common.exception.ApiErrorCodes;
import com.self.multi_currency_household_ledger.common.exception.ErrorCode;
import com.self.multi_currency_household_ledger.exchange.exception.ExchangeErrorCode;
import com.self.multi_currency_household_ledger.ledger.exception.BudgetErrorCode;
import com.self.multi_currency_household_ledger.ledger.exception.LedgerErrorCode;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * {@link ApiErrorCodes} 가 붙은 오퍼레이션에 {@code x-error-codes}(정렬·중복 제거한 문자열 배열)를 싣는다. 키가 있으면 "전부
 * 선언됨", 없으면 "아직 미선언" 이다.
 *
 * <p>바디 판정은 413 계약({@link RequestSizeContractConfig})과 같은 {@code operation.getRequestBody() != null} 이다 — springdoc
 * 은 요청 바디를 만든 뒤에 오퍼레이션 커스터마이저를 부른다({@code AbstractOpenApiResource.calculatePath}).
 */
@Configuration
class ErrorCodeContractConfig {

    private static final String ERROR_CODES = "x-error-codes";

    // FORBIDDEN 은 나오는 경로가 없고, CONCURRENT_MODIFICATION 은 기존 행을 고치거나 지우는 오퍼레이션이 직접 선언한다 —
    // @Version 충돌 말고도, 찾은 행이 flush 전에 cascade 로 사라지면 0행 갱신·삭제가 낙관적 락 실패로 나간다.
    private static final Set<String> COMMON_CODES = Stream.of(
                    ErrorCode.Common.VALIDATION_ERROR,
                    ErrorCode.Common.INVALID_PARAMETER,
                    ErrorCode.Common.MALFORMED_REQUEST,
                    ErrorCode.Common.INTERNAL_ERROR,
                    ErrorCode.Common.UNAUTHORIZED,
                    ErrorCode.Common.TOO_MANY_REQUESTS)
            .map(ErrorCode::getCode)
            .collect(toUnmodifiableSet());

    private static final Set<String> KNOWN_CODES = Stream.<ErrorCode[]>of(
                    ErrorCode.Common.values(),
                    ExchangeErrorCode.values(),
                    BudgetErrorCode.values(),
                    LedgerErrorCode.values())
            .flatMap(Arrays::stream)
            .map(ErrorCode::getCode)
            .collect(toUnmodifiableSet());

    @Bean
    OperationCustomizer errorCodeOperationCustomizer() {
        return (operation, handlerMethod) -> {
            ApiErrorCodes declared = handlerMethod.getMethodAnnotation(ApiErrorCodes.class);
            if (declared == null) {
                return operation;
            }
            SortedSet<String> codes = new TreeSet<>(COMMON_CODES);
            if (operation.getRequestBody() != null) {
                codes.add(ErrorCode.Common.REQUEST_BODY_TOO_LARGE.getCode());
            }
            for (String code : declared.value()) {
                if (!KNOWN_CODES.contains(code)) {
                    throw new IllegalStateException("@ApiErrorCodes 에 알 수 없는 코드 " + code + " — "
                            + handlerMethod.getBeanType().getSimpleName() + "#"
                            + handlerMethod.getMethod().getName());
                }
                codes.add(code);
            }
            operation.addExtension(ERROR_CODES, List.copyOf(codes));
            return operation;
        };
    }
}

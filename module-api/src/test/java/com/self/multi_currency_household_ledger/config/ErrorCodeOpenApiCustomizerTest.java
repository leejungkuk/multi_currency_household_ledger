package com.self.multi_currency_household_ledger.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.InstanceOfAssertFactories.list;

import com.self.multi_currency_household_ledger.common.exception.ApiErrorCodes;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.parameters.RequestBody;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.method.HandlerMethod;

class ErrorCodeOpenApiCustomizerTest {

    private static final String KEY = "x-error-codes";

    private final OperationCustomizer customizer = new ErrorCodeContractConfig().errorCodeOperationCustomizer();

    @Test
    void declaredOperationGetsCommonAndDeclaredCodesSorted() throws NoSuchMethodException {
        Operation operation = customize("declared", String.class);

        assertThat(operation.getExtensions())
                .extractingByKey(KEY, list(String.class))
                .containsExactly(
                        "BUDGET_MONTH_OUT_OF_RANGE",
                        "INTERNAL_ERROR",
                        "INVALID_PARAMETER",
                        "MALFORMED_REQUEST",
                        "TOO_MANY_REQUESTS",
                        "UNAUTHORIZED",
                        "VALIDATION_ERROR")
                .doesNotContain("REQUEST_BODY_TOO_LARGE");
    }

    @Test
    void bodyOperationAddsRequestBodyTooLarge() throws NoSuchMethodException {
        Operation operation = customizer.customize(new Operation().requestBody(new RequestBody()), handler("withBody"));

        assertThat(operation.getExtensions())
                .extractingByKey(KEY, list(String.class))
                .containsExactly(
                        "EXCHANGE_RATE_NOT_FOUND",
                        "INTERNAL_ERROR",
                        "INVALID_PARAMETER",
                        "LEDGER_ENTRY_NOT_FOUND",
                        "MALFORMED_REQUEST",
                        "REQUEST_BODY_TOO_LARGE",
                        "TOO_MANY_REQUESTS",
                        "UNAUTHORIZED",
                        "VALIDATION_ERROR");
    }

    @Test
    void emptyDeclarationStillDeclaresCommonCodes() throws NoSuchMethodException {
        Operation operation = customize("emptyDeclared");

        assertThat(operation.getExtensions())
                .extractingByKey(KEY, list(String.class))
                .containsExactly(
                        "INTERNAL_ERROR",
                        "INVALID_PARAMETER",
                        "MALFORMED_REQUEST",
                        "TOO_MANY_REQUESTS",
                        "UNAUTHORIZED",
                        "VALIDATION_ERROR");
    }

    /** 바디가 있어도 애너테이션이 없으면 키를 만들지 않는다 — "키 없음 = 아직 미선언" 이다. */
    @Test
    void undeclaredOperationHasNoKey() throws NoSuchMethodException {
        Operation operation = new Operation().requestBody(new RequestBody());

        Operation result = customizer.customize(operation, handler("undeclared"));

        assertThat(result).isSameAs(operation);
        assertThat(Objects.requireNonNullElse(result.getExtensions(), Map.of())).doesNotContainKey(KEY);
    }

    @Test
    void unknownCodeFails() {
        assertThatThrownBy(() -> customize("declaresUnknownCode"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NO_SUCH_CODE")
                .hasMessageContaining("declaresUnknownCode");
    }

    @Test
    void duplicatedCommonCodeIsNotRepeated() throws NoSuchMethodException {
        Operation operation = customize("redeclaresCommonCode");

        assertThat(operation.getExtensions())
                .extractingByKey(KEY, list(String.class))
                .containsExactly(
                        "INTERNAL_ERROR",
                        "INVALID_PARAMETER",
                        "MALFORMED_REQUEST",
                        "TOO_MANY_REQUESTS",
                        "UNAUTHORIZED",
                        "VALIDATION_ERROR");
    }

    private Operation customize(String methodName, Class<?>... parameterTypes) throws NoSuchMethodException {
        return customizer.customize(new Operation(), handler(methodName, parameterTypes));
    }

    private static HandlerMethod handler(String methodName, Class<?>... parameterTypes) throws NoSuchMethodException {
        return new HandlerMethod(new FixtureController(), methodName, parameterTypes);
    }

    static class FixtureController {

        /** 바디 아닌 파라미터가 있다 — "파라미터가 있으면 바디" 로 판정하는 구현을 빨갛게 한다. */
        @ApiErrorCodes({"BUDGET_MONTH_OUT_OF_RANGE"})
        public void declared(@RequestParam String month) {}

        /** 바디는 오퍼레이션에만 있다 — 핸들러 파라미터의 {@code @RequestBody} 로 판정하는 구현을 빨갛게 한다. */
        @ApiErrorCodes({"LEDGER_ENTRY_NOT_FOUND", "EXCHANGE_RATE_NOT_FOUND"})
        public void withBody() {}

        @ApiErrorCodes({})
        public void emptyDeclared() {}

        public void undeclared() {}

        @ApiErrorCodes({"NO_SUCH_CODE"})
        public void declaresUnknownCode() {}

        @ApiErrorCodes({"UNAUTHORIZED"})
        public void redeclaresCommonCode() {}
    }
}

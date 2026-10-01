package com.self.multi_currency_household_ledger.common.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ApiErrorCodesTest {

    /** 기본값이 생기면 {@code @ApiErrorCodes({})}(도메인 코드 없음)와 미선언을 구분할 수 없다. "키가 있으면 전부 선언됨" 계약이 이 구분에 기댄다. */
    @Test
    @DisplayName("value 에는 기본값이 없다")
    void value_has_no_default() throws NoSuchMethodException {
        assertThat(ApiErrorCodes.class.getMethod("value").getDefaultValue()).isNull();
    }

    @Test
    @DisplayName("계약 생성기가 런타임에 컨트롤러 메서드에서 읽을 수 있다")
    void is_retained_at_runtime_on_methods() {
        assertThat(ApiErrorCodes.class.getAnnotation(Retention.class).value()).isEqualTo(RetentionPolicy.RUNTIME);
        assertThat(ApiErrorCodes.class.getAnnotation(Target.class).value()).containsExactly(ElementType.METHOD);
    }
}

package com.self.multi_currency_household_ledger.common.annotation;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CurrentMemberIdTest {

    @Test
    @DisplayName("CurrentMemberId는 런타임 파라미터 어노테이션이다")
    void current_member_id_is_runtime_parameter_annotation() {
        Target target = CurrentMemberId.class.getAnnotation(Target.class);
        Retention retention = CurrentMemberId.class.getAnnotation(Retention.class);

        assertThat(target.value()).containsExactly(ElementType.PARAMETER);
        assertThat(retention.value()).isEqualTo(RetentionPolicy.RUNTIME);
    }
}

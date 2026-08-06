package com.self.multi_currency_household_ledger.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 이 테스트가 그린이라는 사실 자체가 검증 대상이다 — 결함은 prod 프로파일에서만 드러나므로 빌드는 통과하고 배포만 죽는다.
 */
class DeliberateCrashProbeTest {

    @Test
    @DisplayName("고의 프로브는 초기화 시 예외를 던져 컨텍스트 생성을 실패시킨다")
    void throws_on_initialization() {
        assertThatThrownBy(() -> new DeliberateCrashProbe().boom())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("revert 대상");
    }
}

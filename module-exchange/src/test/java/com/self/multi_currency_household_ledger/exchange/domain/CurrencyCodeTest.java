package com.self.multi_currency_household_ledger.exchange.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CurrencyCodeTest {

    @Test
    @DisplayName("API 코드로 CurrencyCode를 찾는다")
    void fromCode_returns_matching_currency() {
        assertThat(CurrencyCode.fromCode("USD")).isEqualTo(CurrencyCode.USD);
        assertThat(CurrencyCode.fromCode("JPY(100)")).isEqualTo(CurrencyCode.JPY);
    }

    @Test
    @DisplayName("지원하지 않는 코드면 IllegalArgumentException을 던진다")
    void fromCode_throws_for_unsupported_code() {
        assertThatThrownBy(() -> CurrencyCode.fromCode("AED"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AED");
    }
}

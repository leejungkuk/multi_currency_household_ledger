package com.self.multi_currency_household_ledger.exchange.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.self.multi_currency_household_ledger.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CurrencyCodeTest {

    @ParameterizedTest(name = "{0}: apiCode={1}, unit={2}")
    @CsvSource({
        "KRW, KRW, 1",
        "USD, USD, 1",
        "EUR, EUR, 1",
        "JPY, JPY(100), 100",
        "CNY, CNH, 1",
        "GBP, GBP, 1",
        "THB, THB, 1",
        "HKD, HKD, 1",
        "SGD, SGD, 1",
        "IDR, IDR(100), 100",
        "MYR, MYR, 1",
        "AUD, AUD, 1",
        "NZD, NZD, 1"
    })
    @DisplayName("13종 통화의 apiCode·unit 계약과 fromCode 역매핑을 검증한다")
    void currency_contract_and_fromCode_roundtrip(CurrencyCode code, String apiCode, int unit) {
        assertThat(code.getApiCode()).isEqualTo(apiCode);
        assertThat(code.getUnit()).isEqualTo(unit);
        assertThat(CurrencyCode.fromCode(apiCode)).isEqualTo(code);
    }

    @Test
    @DisplayName("지원 통화는 정확히 13종이다")
    void supports_exactly_thirteen_currencies() {
        assertThat(CurrencyCode.values()).hasSize(13);
    }

    @Test
    @DisplayName("지원하지 않는 코드면 BusinessException을 던진다 — 위안화 apiCode는 CNH라 CNY 문자열도 미지원")
    void fromCode_throws_for_unsupported_code() {
        assertThatThrownBy(() -> CurrencyCode.fromCode("AED"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("지원하지 않는 통화");
        assertThatThrownBy(() -> CurrencyCode.fromCode("CNY"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("지원하지 않는 통화");
    }
}

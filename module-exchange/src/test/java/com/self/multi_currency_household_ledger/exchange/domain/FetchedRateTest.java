package com.self.multi_currency_household_ledger.exchange.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FetchedRateTest {

    @Test
    @DisplayName("통화 코드와 환율을 보유한다")
    void holds_currency_code_and_rate() {
        FetchedRate rate = new FetchedRate(CurrencyCode.USD, new BigDecimal("1300.00"));

        assertThat(rate.currencyCode()).isEqualTo(CurrencyCode.USD);
        assertThat(rate.tts()).isEqualByComparingTo(new BigDecimal("1300.00"));
    }
}

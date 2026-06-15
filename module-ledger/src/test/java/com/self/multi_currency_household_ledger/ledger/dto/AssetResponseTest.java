package com.self.multi_currency_household_ledger.ledger.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AssetResponseTest {

    @Test
    void test() {
        assertThat(new AssetResponse(1L, "card", "카드", "💳", 1)).isNotNull();
    }
}

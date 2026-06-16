package com.self.multi_currency_household_ledger.ledger.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AssetResponseTest {

    @Test
    void exposes_ko_and_en_display_names_without_icon() {
        AssetResponse response = new AssetResponse(1L, "CREDIT_CARD", "신용카드", "Credit Card", 1);

        assertThat(response.displayNameKo()).isEqualTo("신용카드");
        assertThat(response.displayNameEn()).isEqualTo("Credit Card");
    }
}

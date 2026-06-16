package com.self.multi_currency_household_ledger.ledger.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CategoryResponseTest {

    @Test
    void exposes_ko_and_en_display_names() {
        CategoryResponse response = new CategoryResponse(1L, "FOOD_DINING", "식비", "Food & Dining", "🍽️", 1);

        assertThat(response.displayNameKo()).isEqualTo("식비");
        assertThat(response.displayNameEn()).isEqualTo("Food & Dining");
    }
}

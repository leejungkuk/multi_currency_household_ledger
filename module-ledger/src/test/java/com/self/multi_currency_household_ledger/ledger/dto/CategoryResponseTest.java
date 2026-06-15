package com.self.multi_currency_household_ledger.ledger.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CategoryResponseTest {

    @Test
    void test() {
        assertThat(new CategoryResponse(1L, "food", "식비", "🍔", 1)).isNotNull();
    }
}

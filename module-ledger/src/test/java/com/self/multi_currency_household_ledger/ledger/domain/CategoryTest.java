package com.self.multi_currency_household_ledger.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CategoryTest {

    // 카테고리 생성 시 기본값이 올바르게 설정되는지 확인한다.
    @Test
    @DisplayName("카테고리를 생성하면 활성화 상태(isActive=true)로 생성된다")
    void create_category_success() {
        Category category = new Category(TransactionType.EXPENSE, "FOOD", "식비", "icon-food", 1, 1L);

        assertThat(category.getTransactionType()).isEqualTo(TransactionType.EXPENSE);
        assertThat(category.getCode()).isEqualTo("FOOD");
        assertThat(category.isActive()).isTrue();
    }
}

package com.self.multi_currency_household_ledger.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CategoryTest {

    // 카테고리 생성 시 기본값이 올바르게 설정되는지 확인한다.
    @Test
    @DisplayName("카테고리를 생성하면 활성화 상태(isActive=true)로 생성된다")
    void create_category_success() {
        Category category = new Category(TransactionType.EXPENSE, "FOOD", "식비", "Food", "icon-food", 1);

        assertThat(category.getTransactionType()).isEqualTo(TransactionType.EXPENSE);
        assertThat(category.getCode()).isEqualTo("FOOD");
        assertThat(category.getDisplayNameKo()).isEqualTo("식비");
        assertThat(category.getDisplayNameEn()).isEqualTo("Food");
        assertThat(category.isActive()).isTrue();
    }

    @Test
    @DisplayName("커스텀 카테고리를 생성하면 전달한 정보와 커스텀 기본값이 설정된다")
    void create_custom_category_success() {
        UUID ownerMemberId = UUID.randomUUID();
        TransactionType transactionType = TransactionType.EXPENSE;
        String name = "반려동물";
        String icon = "icon-pet";

        Category category = Category.custom(ownerMemberId, transactionType, name, icon);

        assertThat(category.getOwnerMemberId()).isEqualTo(ownerMemberId);
        assertThat(category.getCode()).isEqualTo("CUSTOM");
        assertThat(category.getDisplayNameKo()).isEqualTo(name);
        assertThat(category.getDisplayNameEn()).isEqualTo(name);
        assertThat(category.getIcon()).isEqualTo(icon);
        assertThat(category.getSortOrder()).isEqualTo(1000);
        assertThat(category.isActive()).isTrue();
        assertThat(category.getTransactionType()).isEqualTo(transactionType);
    }
}

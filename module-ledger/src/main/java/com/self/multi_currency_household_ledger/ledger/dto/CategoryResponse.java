package com.self.multi_currency_household_ledger.ledger.dto;

import com.self.multi_currency_household_ledger.ledger.domain.Category;

public record CategoryResponse(
        Long id, String code, String displayNameKo, String displayNameEn, String icon, int sortOrder) {
    public static CategoryResponse from(Category category) {
        return new CategoryResponse(
                category.getId(),
                category.getCode(),
                category.getDisplayNameKo(),
                category.getDisplayNameEn(),
                category.getIcon(),
                category.getSortOrder());
    }
}

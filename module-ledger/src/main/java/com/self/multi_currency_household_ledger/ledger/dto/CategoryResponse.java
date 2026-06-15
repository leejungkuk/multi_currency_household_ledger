package com.self.multi_currency_household_ledger.ledger.dto;

import com.self.multi_currency_household_ledger.ledger.domain.Category;

public record CategoryResponse(Long id, String code, String displayName, String icon, int sortOrder) {
    public static CategoryResponse from(Category category) {
        return new CategoryResponse(
                category.getId(),
                category.getCode(),
                category.getDisplayName(),
                category.getIcon(),
                category.getSortOrder());
    }
}

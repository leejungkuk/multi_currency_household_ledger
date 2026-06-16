package com.self.multi_currency_household_ledger.ledger.dto;

import com.self.multi_currency_household_ledger.ledger.domain.Asset;

public record AssetResponse(Long id, String code, String displayNameKo, String displayNameEn, int sortOrder) {
    public static AssetResponse from(Asset asset) {
        return new AssetResponse(
                asset.getId(),
                asset.getCode(),
                asset.getDisplayNameKo(),
                asset.getDisplayNameEn(),
                asset.getSortOrder());
    }
}

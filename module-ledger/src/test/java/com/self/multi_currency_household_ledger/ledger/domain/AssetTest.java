package com.self.multi_currency_household_ledger.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AssetTest {

    // 자산 생성 시 기본값이 올바르게 설정되는지 확인한다.
    @Test
    @DisplayName("자산을 생성하면 활성화 상태(isActive=true)로 생성된다")
    void create_asset_success() {
        Asset asset = new Asset("CASH", "현금", "Cash", 1);

        assertThat(asset.getCode()).isEqualTo("CASH");
        assertThat(asset.getDisplayNameKo()).isEqualTo("현금");
        assertThat(asset.getDisplayNameEn()).isEqualTo("Cash");
        assertThat(asset.isActive()).isTrue();
    }
}

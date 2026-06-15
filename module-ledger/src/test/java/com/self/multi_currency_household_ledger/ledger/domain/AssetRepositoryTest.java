package com.self.multi_currency_household_ledger.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;

import com.self.multi_currency_household_ledger.ledger.TestJpaConfig;
import com.self.multi_currency_household_ledger.ledger.TestLedgerApplication;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@Import({TestLedgerApplication.class, TestJpaConfig.class})
class AssetRepositoryTest {

    @Autowired
    private AssetRepository assetRepository;

    // 특정 회원의 활성화된 자산 목록을 조회한다.
    @Test
    @DisplayName("소유자와 공통(0L) 소유의 활성화된 자산을 조회할 수 있다")
    void find_assets_by_owner() {
        assetRepository.save(new Asset("CASH", "현금", "icon-cash", 1, 0L));
        assetRepository.save(new Asset("CARD", "신용카드", "icon-card", 2, 1L));

        List<Asset> assets = assetRepository.findByOwnerMemberIdInAndIsActiveTrueOrderBySortOrder(List.of(0L, 1L));

        assertThat(assets).hasSize(2);
        assertThat(assets.get(0).getCode()).isEqualTo("CASH");
        assertThat(assets.get(1).getCode()).isEqualTo("CARD");
    }
}

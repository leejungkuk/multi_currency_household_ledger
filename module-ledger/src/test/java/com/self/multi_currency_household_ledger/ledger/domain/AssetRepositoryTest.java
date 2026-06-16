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

    // 공용 고정 카탈로그의 활성화된 자산 목록을 조회한다.
    @Test
    @DisplayName("공용 활성화 자산을 sort_order 순서로 조회할 수 있다")
    void find_assets_as_shared_catalog() {
        assetRepository.save(new Asset("TEST_CASH", "현금", "Cash", 100));
        assetRepository.save(new Asset("TEST_CARD", "카드", "Card", 101));

        List<Asset> assets = assetRepository.findByIsActiveTrueOrderBySortOrder();

        assertThat(assets).extracting(Asset::getCode).containsSubsequence("TEST_CASH", "TEST_CARD");
    }
}

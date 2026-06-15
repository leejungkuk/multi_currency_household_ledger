package com.self.multi_currency_household_ledger.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

import com.self.multi_currency_household_ledger.ledger.domain.Asset;
import com.self.multi_currency_household_ledger.ledger.domain.AssetRepository;
import com.self.multi_currency_household_ledger.ledger.domain.Category;
import com.self.multi_currency_household_ledger.ledger.domain.CategoryRepository;
import com.self.multi_currency_household_ledger.ledger.domain.TransactionType;
import com.self.multi_currency_household_ledger.ledger.dto.AssetResponse;
import com.self.multi_currency_household_ledger.ledger.dto.CategoryResponse;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CatalogServiceTest {

    @Mock
    private CategoryRepository categoryRepository;

    @Mock
    private AssetRepository assetRepository;

    @InjectMocks
    private CatalogService catalogService;

    // 거래 유형별 카테고리 목록을 DTO로 변환해 반환한다.
    @Test
    @DisplayName("거래 유형별 카테고리 목록을 조회해 응답 DTO로 반환한다")
    void get_categories() {
        Category category =
                new Category(TransactionType.EXPENSE, "FOOD", "식비", "icon-food", 1, Category.SYSTEM_OWNER_ID);
        given(categoryRepository.findByTransactionTypeAndOwnerMemberIdAndIsActiveTrueOrderBySortOrder(
                        eq(TransactionType.EXPENSE), eq(Category.SYSTEM_OWNER_ID)))
                .willReturn(List.of(category));

        List<CategoryResponse> responses = catalogService.getCategories(TransactionType.EXPENSE);

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).code()).isEqualTo("FOOD");
        assertThat(responses.get(0).displayName()).isEqualTo("식비");
    }

    // 자산 목록을 DTO로 변환해 반환한다.
    @Test
    @DisplayName("자산 목록을 조회해 응답 DTO로 반환한다")
    void get_assets() {
        Asset asset = new Asset("CASH", "현금", "icon-cash", 1, Asset.SYSTEM_OWNER_ID);
        given(assetRepository.findByOwnerMemberIdAndIsActiveTrueOrderBySortOrder(eq(Asset.SYSTEM_OWNER_ID)))
                .willReturn(List.of(asset));

        List<AssetResponse> responses = catalogService.getAssets();

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).code()).isEqualTo("CASH");
        assertThat(responses.get(0).displayName()).isEqualTo("현금");
    }
}

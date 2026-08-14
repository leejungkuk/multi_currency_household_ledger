package com.self.multi_currency_household_ledger.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.self.multi_currency_household_ledger.common.exception.BusinessException;
import com.self.multi_currency_household_ledger.ledger.domain.Asset;
import com.self.multi_currency_household_ledger.ledger.domain.AssetRepository;
import com.self.multi_currency_household_ledger.ledger.domain.Category;
import com.self.multi_currency_household_ledger.ledger.domain.CategoryRepository;
import com.self.multi_currency_household_ledger.ledger.domain.TransactionType;
import com.self.multi_currency_household_ledger.ledger.dto.AssetResponse;
import com.self.multi_currency_household_ledger.ledger.dto.CategoryResponse;
import com.self.multi_currency_household_ledger.ledger.dto.CreateCustomCategoryRequest;
import com.self.multi_currency_household_ledger.ledger.exception.LedgerErrorCode;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CatalogServiceTest {

    private static final UUID MEMBER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

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
        Category category = new Category(TransactionType.EXPENSE, "FOOD_DINING", "식비", "Food & Dining", "🍽️", 1);
        given(categoryRepository.findByOwnerMemberIdIsNullAndTransactionTypeAndIsActiveTrueOrderBySortOrder(
                        TransactionType.EXPENSE))
                .willReturn(List.of(category));

        List<CategoryResponse> responses = catalogService.getCategories(TransactionType.EXPENSE);

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).code()).isEqualTo("FOOD_DINING");
        assertThat(responses.get(0).displayNameKo()).isEqualTo("식비");
        assertThat(responses.get(0).displayNameEn()).isEqualTo("Food & Dining");
        then(categoryRepository)
                .should()
                .findByOwnerMemberIdIsNullAndTransactionTypeAndIsActiveTrueOrderBySortOrder(TransactionType.EXPENSE);
    }

    // 자산 목록을 DTO로 변환해 반환한다.
    @Test
    @DisplayName("자산 목록을 조회해 응답 DTO로 반환한다")
    void get_assets() {
        Asset asset = new Asset("CASH", "현금", "Cash", 3);
        given(assetRepository.findByIsActiveTrueOrderBySortOrder()).willReturn(List.of(asset));

        List<AssetResponse> responses = catalogService.getAssets();

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).code()).isEqualTo("CASH");
        assertThat(responses.get(0).displayNameKo()).isEqualTo("현금");
        assertThat(responses.get(0).displayNameEn()).isEqualTo("Cash");
        then(assetRepository).should().findByIsActiveTrueOrderBySortOrder();
    }

    @Test
    @DisplayName("내 활성 커스텀 카테고리를 거래 유형별 생성순으로 조회한다")
    void get_custom_categories() {
        Category category = Category.custom(MEMBER_ID, TransactionType.EXPENSE, "반려견", "🐶");
        given(categoryRepository.findByOwnerMemberIdAndTransactionTypeAndIsActiveTrueOrderById(
                        MEMBER_ID, TransactionType.EXPENSE))
                .willReturn(List.of(category));

        List<CategoryResponse> responses = catalogService.getCustomCategories(MEMBER_ID, TransactionType.EXPENSE);

        assertThat(responses).extracting(CategoryResponse::displayNameKo).containsExactly("반려견");
        then(categoryRepository)
                .should()
                .findByOwnerMemberIdAndTransactionTypeAndIsActiveTrueOrderById(MEMBER_ID, TransactionType.EXPENSE);
    }

    @Test
    @DisplayName("커스텀 카테고리를 생성하면 이름과 커스텀 기본값을 응답한다")
    void create_custom_category() {
        CreateCustomCategoryRequest request = new CreateCustomCategoryRequest(TransactionType.EXPENSE, "반려견", "🐶");
        given(categoryRepository.countByOwnerMemberIdAndIsActiveTrue(MEMBER_ID)).willReturn(99L);
        given(categoryRepository.save(any(Category.class))).willAnswer(invocation -> invocation.getArgument(0));

        CategoryResponse response = catalogService.createCustomCategory(MEMBER_ID, request);

        assertThat(response.code()).isEqualTo("CUSTOM");
        assertThat(response.displayNameKo()).isEqualTo("반려견");
        assertThat(response.displayNameEn()).isEqualTo("반려견");
        assertThat(response.icon()).isEqualTo("🐶");
        assertThat(response.sortOrder()).isEqualTo(1000);
    }

    @Test
    @DisplayName("활성 커스텀 카테고리가 100개면 생성 요청을 403으로 거부한다")
    void create_custom_category_rejects_limit() {
        CreateCustomCategoryRequest request = new CreateCustomCategoryRequest(TransactionType.EXPENSE, "상한 초과", null);
        given(categoryRepository.countByOwnerMemberIdAndIsActiveTrue(MEMBER_ID)).willReturn(100L);

        assertThatThrownBy(() -> catalogService.createCustomCategory(MEMBER_ID, request))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> {
                    BusinessException businessException = (BusinessException) exception;
                    assertThat(businessException.getCode())
                            .isEqualTo(LedgerErrorCode.CUSTOM_CATEGORY_LIMIT_EXCEEDED.getCode());
                    assertThat(businessException.getHttpStatus().value()).isEqualTo(403);
                });
        then(categoryRepository).should(never()).save(any(Category.class));
    }

    @Test
    @DisplayName("삭제 대상이 없거나 타 회원 소유면 CATEGORY_NOT_FOUND를 반환한다")
    void delete_custom_category_rejects_missing_category() {
        given(categoryRepository.findByIdAndOwnerMemberId(10_000L, MEMBER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> catalogService.deleteCustomCategory(MEMBER_ID, 10_000L))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getCode())
                .isEqualTo(LedgerErrorCode.CATEGORY_NOT_FOUND.getCode());
    }

    @Test
    @DisplayName("이미 비활성인 내 커스텀 카테고리 삭제는 정상 종료한다")
    void delete_custom_category_is_idempotent_when_already_inactive() {
        Category category = Category.custom(MEMBER_ID, TransactionType.EXPENSE, "삭제됨", null);
        category.deactivate();
        given(categoryRepository.findByIdAndOwnerMemberId(10_000L, MEMBER_ID)).willReturn(Optional.of(category));

        catalogService.deleteCustomCategory(MEMBER_ID, 10_000L);

        assertThat(category.isActive()).isFalse();
    }
}

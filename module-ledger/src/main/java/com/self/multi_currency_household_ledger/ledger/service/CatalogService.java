package com.self.multi_currency_household_ledger.ledger.service;

import com.self.multi_currency_household_ledger.common.exception.BusinessException;
import com.self.multi_currency_household_ledger.ledger.domain.AssetRepository;
import com.self.multi_currency_household_ledger.ledger.domain.Category;
import com.self.multi_currency_household_ledger.ledger.domain.CategoryRepository;
import com.self.multi_currency_household_ledger.ledger.domain.TransactionType;
import com.self.multi_currency_household_ledger.ledger.dto.AssetResponse;
import com.self.multi_currency_household_ledger.ledger.dto.CategoryResponse;
import com.self.multi_currency_household_ledger.ledger.dto.CreateCustomCategoryRequest;
import com.self.multi_currency_household_ledger.ledger.dto.ReorderCustomCategoriesRequest;
import com.self.multi_currency_household_ledger.ledger.dto.UpdateCustomCategoryRequest;
import com.self.multi_currency_household_ledger.ledger.exception.LedgerErrorCode;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 카테고리·자산 목록 조회 오케스트레이션. Controller는 Repository를 직접 의존하지 않는다. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CatalogService {

    /** 재정렬 값의 시작점. 신규·미정렬 커스텀은 {@code Category.custom} 의 1000이라 재정렬된 항목보다 항상 앞선다. */
    private static final int REORDERED_SORT_ORDER_BASE = 1001;

    private static final Comparator<Category> BY_LISTING_ORDER = Comparator.<Category>comparingInt(
                    Category::getSortOrder)
            .thenComparing(Category::getId, Comparator.reverseOrder());

    private final CategoryRepository categoryRepository;
    private final AssetRepository assetRepository;

    public List<CategoryResponse> getCategories(TransactionType transactionType) {
        return categoryRepository
                .findByOwnerMemberIdIsNullAndTransactionTypeAndIsActiveTrueOrderBySortOrder(transactionType)
                .stream()
                .map(CategoryResponse::from)
                .toList();
    }

    public List<AssetResponse> getAssets() {
        return assetRepository.findByIsActiveTrueOrderBySortOrder().stream()
                .map(AssetResponse::from)
                .toList();
    }

    public List<CategoryResponse> getCustomCategories(UUID memberId, TransactionType transactionType) {
        return categoryRepository
                .findByOwnerMemberIdAndTransactionTypeAndIsActiveTrueOrderBySortOrderAscIdDesc(
                        memberId, transactionType)
                .stream()
                .map(CategoryResponse::from)
                .toList();
    }

    @Transactional
    public CategoryResponse createCustomCategory(UUID memberId, CreateCustomCategoryRequest request) {
        if (categoryRepository.countByOwnerMemberIdAndIsActiveTrue(memberId) >= Category.CUSTOM_LIMIT) {
            throw new BusinessException(LedgerErrorCode.CUSTOM_CATEGORY_LIMIT_EXCEEDED);
        }
        Category category = Category.custom(memberId, request.transactionType(), request.name(), request.icon());
        return CategoryResponse.from(categoryRepository.save(category));
    }

    @Transactional
    public CategoryResponse updateCustomCategory(UUID memberId, Long categoryId, UpdateCustomCategoryRequest request) {
        Category category = categoryRepository
                .findByIdAndOwnerMemberIdAndIsActiveTrue(categoryId, memberId)
                .orElseThrow(() -> new BusinessException(LedgerErrorCode.CATEGORY_NOT_FOUND));
        category.rename(request.name(), request.icon());
        return CategoryResponse.from(category);
    }

    @Transactional
    public List<CategoryResponse> reorderCustomCategories(UUID memberId, ReorderCustomCategoriesRequest request) {
        // 3술어(owner·유형·활성) 단일 정렬 쿼리 1회. id별 조회 루프는 동시 재정렬끼리 UPDATE 잠금 순서가 엇갈려 교착한다.
        List<Category> categories =
                categoryRepository.findByOwnerMemberIdAndTransactionTypeAndIsActiveTrueOrderBySortOrderAscIdDesc(
                        memberId, request.transactionType());
        Map<Long, Category> byId = categories.stream().collect(Collectors.toMap(Category::getId, Function.identity()));
        List<Long> orderedIds = request.orderedIds().stream().distinct().toList();
        if (!byId.keySet().containsAll(orderedIds)) {
            throw new BusinessException(LedgerErrorCode.CATEGORY_NOT_FOUND);
        }
        for (int index = 0; index < orderedIds.size(); index++) {
            byId.get(orderedIds.get(index)).applySortOrder(REORDERED_SORT_ORDER_BASE + index);
        }
        // 로드 순서는 재정렬 이전 값이라 그대로 반환하면 옛 순서가 나간다(dirty checking flush 전).
        return categories.stream()
                .sorted(BY_LISTING_ORDER)
                .map(CategoryResponse::from)
                .toList();
    }

    @Transactional
    public void deleteCustomCategory(UUID memberId, Long categoryId) {
        Category category = categoryRepository
                .findByIdAndOwnerMemberId(categoryId, memberId)
                .orElseThrow(() -> new BusinessException(LedgerErrorCode.CATEGORY_NOT_FOUND));
        category.deactivate();
    }
}

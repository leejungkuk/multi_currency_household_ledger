package com.self.multi_currency_household_ledger.ledger.service;

import com.self.multi_currency_household_ledger.common.exception.BusinessException;
import com.self.multi_currency_household_ledger.ledger.domain.AssetRepository;
import com.self.multi_currency_household_ledger.ledger.domain.Category;
import com.self.multi_currency_household_ledger.ledger.domain.CategoryRepository;
import com.self.multi_currency_household_ledger.ledger.domain.TransactionType;
import com.self.multi_currency_household_ledger.ledger.dto.AssetResponse;
import com.self.multi_currency_household_ledger.ledger.dto.CategoryResponse;
import com.self.multi_currency_household_ledger.ledger.dto.CreateCustomCategoryRequest;
import com.self.multi_currency_household_ledger.ledger.exception.LedgerErrorCode;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 카테고리·자산 목록 조회 오케스트레이션. Controller는 Repository를 직접 의존하지 않는다. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CatalogService {

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
                .findByOwnerMemberIdAndTransactionTypeAndIsActiveTrueOrderById(memberId, transactionType)
                .stream()
                .map(CategoryResponse::from)
                .toList();
    }

    @Transactional
    public CategoryResponse createCustomCategory(UUID memberId, CreateCustomCategoryRequest request) {
        if (categoryRepository.countByOwnerMemberIdAndIsActiveTrue(memberId) >= 100) {
            throw new BusinessException(LedgerErrorCode.CUSTOM_CATEGORY_LIMIT_EXCEEDED);
        }
        Category category = Category.custom(memberId, request.transactionType(), request.name(), request.icon());
        return CategoryResponse.from(categoryRepository.save(category));
    }

    @Transactional
    public void deleteCustomCategory(UUID memberId, Long categoryId) {
        Category category = categoryRepository
                .findByIdAndOwnerMemberId(categoryId, memberId)
                .orElseThrow(() -> new BusinessException(LedgerErrorCode.CATEGORY_NOT_FOUND));
        category.deactivate();
    }
}

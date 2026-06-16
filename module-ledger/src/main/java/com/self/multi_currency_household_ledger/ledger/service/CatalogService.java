package com.self.multi_currency_household_ledger.ledger.service;

import com.self.multi_currency_household_ledger.ledger.domain.AssetRepository;
import com.self.multi_currency_household_ledger.ledger.domain.CategoryRepository;
import com.self.multi_currency_household_ledger.ledger.domain.TransactionType;
import com.self.multi_currency_household_ledger.ledger.dto.AssetResponse;
import com.self.multi_currency_household_ledger.ledger.dto.CategoryResponse;
import java.util.List;
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
        return categoryRepository.findByTransactionTypeAndIsActiveTrueOrderBySortOrder(transactionType).stream()
                .map(CategoryResponse::from)
                .toList();
    }

    public List<AssetResponse> getAssets() {
        return assetRepository.findByIsActiveTrueOrderBySortOrder().stream()
                .map(AssetResponse::from)
                .toList();
    }
}

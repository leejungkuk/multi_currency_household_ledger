package com.self.multi_currency_household_ledger.ledger.controller;

import com.self.multi_currency_household_ledger.common.dto.ApiResponse;
import com.self.multi_currency_household_ledger.ledger.domain.TransactionType;
import com.self.multi_currency_household_ledger.ledger.dto.AssetResponse;
import com.self.multi_currency_household_ledger.ledger.dto.CategoryResponse;
import com.self.multi_currency_household_ledger.ledger.service.CatalogService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
public class CatalogController {

    private final CatalogService catalogService;

    @GetMapping("/categories")
    public ApiResponse<List<CategoryResponse>> getCategories(
            @RequestParam("transactionType") TransactionType transactionType) {
        Long memberId = 1L; // TODO: Spring Security 적용 후 @AuthenticationPrincipal 등으로 대체
        return ApiResponse.success(catalogService.getCategories(transactionType, memberId));
    }

    @GetMapping("/assets")
    public ApiResponse<List<AssetResponse>> getAssets() {
        Long memberId = 1L; // TODO: Spring Security 적용 후 @AuthenticationPrincipal 등으로 대체
        return ApiResponse.success(catalogService.getAssets(memberId));
    }
}

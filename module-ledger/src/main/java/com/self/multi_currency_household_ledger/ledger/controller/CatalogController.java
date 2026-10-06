package com.self.multi_currency_household_ledger.ledger.controller;

import com.self.multi_currency_household_ledger.common.annotation.CurrentMemberId;
import com.self.multi_currency_household_ledger.common.dto.ApiResponse;
import com.self.multi_currency_household_ledger.common.exception.ApiErrorCodes;
import com.self.multi_currency_household_ledger.common.web.CacheControlHeaders;
import com.self.multi_currency_household_ledger.ledger.domain.TransactionType;
import com.self.multi_currency_household_ledger.ledger.dto.AssetResponse;
import com.self.multi_currency_household_ledger.ledger.dto.CategoryResponse;
import com.self.multi_currency_household_ledger.ledger.dto.CreateCustomCategoryRequest;
import com.self.multi_currency_household_ledger.ledger.dto.ReorderCustomCategoriesRequest;
import com.self.multi_currency_household_ledger.ledger.dto.UpdateCustomCategoryRequest;
import com.self.multi_currency_household_ledger.ledger.service.CatalogService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1")
public class CatalogController {

    private final CatalogService catalogService;

    @GetMapping("/categories")
    @ApiErrorCodes({})
    public ResponseEntity<ApiResponse<List<CategoryResponse>>> getCategories(
            @RequestParam("transactionType") TransactionType transactionType) {
        return publicRead(catalogService.getCategories(transactionType));
    }

    @GetMapping("/categories/custom")
    @ApiErrorCodes({})
    public ApiResponse<List<CategoryResponse>> getCustomCategories(
            @CurrentMemberId UUID memberId, @RequestParam("transactionType") TransactionType transactionType) {
        return ApiResponse.success(catalogService.getCustomCategories(memberId, transactionType));
    }

    @PostMapping("/categories/custom")
    @ApiErrorCodes({"CUSTOM_CATEGORY_LIMIT_EXCEEDED"})
    public ApiResponse<CategoryResponse> createCustomCategory(
            @CurrentMemberId UUID memberId, @Valid @RequestBody CreateCustomCategoryRequest request) {
        return ApiResponse.success(catalogService.createCustomCategory(memberId, request));
    }

    @PutMapping("/categories/custom/{id}")
    @ApiErrorCodes({"CATEGORY_NOT_FOUND", "CONCURRENT_MODIFICATION"})
    public ApiResponse<CategoryResponse> updateCustomCategory(
            @CurrentMemberId UUID memberId,
            @PathVariable("id") Long categoryId,
            @Valid @RequestBody UpdateCustomCategoryRequest request) {
        return ApiResponse.success(catalogService.updateCustomCategory(memberId, categoryId, request));
    }

    @PutMapping("/categories/custom/order")
    @ApiErrorCodes({"CATEGORY_NOT_FOUND", "CONCURRENT_MODIFICATION"})
    public ApiResponse<List<CategoryResponse>> reorderCustomCategories(
            @CurrentMemberId UUID memberId, @Valid @RequestBody ReorderCustomCategoriesRequest request) {
        return ApiResponse.success(catalogService.reorderCustomCategories(memberId, request));
    }

    @DeleteMapping("/categories/custom/{id}")
    @ApiErrorCodes({"CATEGORY_NOT_FOUND", "CONCURRENT_MODIFICATION"})
    public ApiResponse<Void> deleteCustomCategory(@CurrentMemberId UUID memberId, @PathVariable("id") Long categoryId) {
        catalogService.deleteCustomCategory(memberId, categoryId);
        return ApiResponse.success(null);
    }

    @GetMapping("/assets")
    @ApiErrorCodes({})
    public ResponseEntity<ApiResponse<List<AssetResponse>>> getAssets() {
        return publicRead(catalogService.getAssets());
    }

    private static <T> ResponseEntity<ApiResponse<T>> publicRead(T data) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, CacheControlHeaders.PUBLIC_READ)
                .body(ApiResponse.success(data));
    }
}

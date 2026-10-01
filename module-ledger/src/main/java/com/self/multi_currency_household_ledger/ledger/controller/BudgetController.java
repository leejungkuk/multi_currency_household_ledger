package com.self.multi_currency_household_ledger.ledger.controller;

import com.self.multi_currency_household_ledger.common.annotation.CurrentMemberId;
import com.self.multi_currency_household_ledger.common.dto.ApiResponse;
import com.self.multi_currency_household_ledger.ledger.dto.MonthlyBudgetResponse;
import com.self.multi_currency_household_ledger.ledger.dto.SaveBudgetRequest;
import com.self.multi_currency_household_ledger.ledger.service.BudgetService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.YearMonth;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/budgets")
@Validated
public class BudgetController {

    private final BudgetService budgetService;

    @GetMapping
    public ApiResponse<MonthlyBudgetResponse> getMonthlyBudget(
            @CurrentMemberId UUID memberId,
            @RequestParam("year") @Min(1900) @Max(9999) int year,
            @RequestParam("month") @Min(1) @Max(12) int month) {
        return ApiResponse.success(budgetService.getMonthlyBudget(memberId, YearMonth.of(year, month)));
    }

    @PutMapping
    public ApiResponse<MonthlyBudgetResponse> saveBudget(
            @CurrentMemberId UUID memberId,
            @RequestParam("year") @Min(1900) @Max(9999) int year,
            @RequestParam("month") @Min(1) @Max(12) int month,
            @Valid @RequestBody SaveBudgetRequest request) {
        return ApiResponse.success(budgetService.save(memberId, YearMonth.of(year, month), request));
    }

    @DeleteMapping
    public ApiResponse<MonthlyBudgetResponse> deleteBudget(
            @CurrentMemberId UUID memberId,
            @RequestParam("year") @Min(1900) @Max(9999) int year,
            @RequestParam("month") @Min(1) @Max(12) int month) {
        return ApiResponse.success(budgetService.delete(memberId, YearMonth.of(year, month)));
    }
}

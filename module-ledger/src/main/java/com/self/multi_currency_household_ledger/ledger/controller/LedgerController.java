package com.self.multi_currency_household_ledger.ledger.controller;

import com.self.multi_currency_household_ledger.common.annotation.CurrentMemberId;
import com.self.multi_currency_household_ledger.common.dto.ApiResponse;
import com.self.multi_currency_household_ledger.ledger.dto.CreateLedgerEntryRequest;
import com.self.multi_currency_household_ledger.ledger.dto.LedgerEntryResponse;
import com.self.multi_currency_household_ledger.ledger.dto.LedgerMonthlySummaryResponse;
import com.self.multi_currency_household_ledger.ledger.dto.LedgerReportResponse;
import com.self.multi_currency_household_ledger.ledger.service.LedgerService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
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
@RequestMapping("/api/v1/ledgers")
@Validated
public class LedgerController {

    private final LedgerService ledgerService;

    @PostMapping
    public ApiResponse<LedgerEntryResponse> createLedgerEntry(
            @CurrentMemberId UUID memberId, @Valid @RequestBody CreateLedgerEntryRequest request) {
        LedgerEntryResponse response = ledgerService.create(request, memberId);
        return ApiResponse.success(response);
    }

    @PutMapping("/{id}")
    public ApiResponse<LedgerEntryResponse> updateLedgerEntry(
            @CurrentMemberId UUID memberId,
            @PathVariable("id") Long id,
            @Valid @RequestBody CreateLedgerEntryRequest request) {
        LedgerEntryResponse response = ledgerService.update(id, request, memberId);
        return ApiResponse.success(response);
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteLedgerEntry(@CurrentMemberId UUID memberId, @PathVariable("id") Long id) {
        ledgerService.delete(id, memberId);
        return ApiResponse.success(null);
    }

    @GetMapping("/summary")
    public ApiResponse<LedgerMonthlySummaryResponse> getMonthlySummary(
            @CurrentMemberId UUID memberId,
            @RequestParam("year") @Min(1900) @Max(9999) int year,
            @RequestParam("month") @Min(1) @Max(12) int month) {
        LedgerMonthlySummaryResponse response = ledgerService.getMonthlySummary(memberId, year, month);
        return ApiResponse.success(response);
    }

    @GetMapping
    public ApiResponse<List<LedgerEntryResponse>> getMonthlyEntries(
            @CurrentMemberId UUID memberId,
            @RequestParam("year") @Min(1900) @Max(9999) int year,
            @RequestParam("month") @Min(1) @Max(12) int month) {
        List<LedgerEntryResponse> response = ledgerService.getMonthlyEntries(memberId, year, month);
        return ApiResponse.success(response);
    }

    @GetMapping("/report")
    public ApiResponse<LedgerReportResponse> getMonthlyReport(
            @CurrentMemberId UUID memberId,
            @RequestParam("year") @Min(1900) @Max(9999) int year,
            @RequestParam("month") @Min(1) @Max(12) int month) {
        LedgerReportResponse response = ledgerService.getMonthlyReport(memberId, year, month);
        return ApiResponse.success(response);
    }
}

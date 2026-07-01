package com.self.multi_currency_household_ledger.ledger.controller;

import com.self.multi_currency_household_ledger.common.annotation.CurrentMemberId;
import com.self.multi_currency_household_ledger.common.dto.ApiResponse;
import com.self.multi_currency_household_ledger.ledger.dto.CreateLedgerEntryRequest;
import com.self.multi_currency_household_ledger.ledger.dto.ImportLedgerEntriesRequest;
import com.self.multi_currency_household_ledger.ledger.dto.ImportLedgerEntriesResponse;
import com.self.multi_currency_household_ledger.ledger.dto.LedgerChangesResponse;
import com.self.multi_currency_household_ledger.ledger.dto.LedgerEntryResponse;
import com.self.multi_currency_household_ledger.ledger.dto.LedgerMonthlySummaryResponse;
import com.self.multi_currency_household_ledger.ledger.dto.LedgerReportResponse;
import com.self.multi_currency_household_ledger.ledger.dto.LedgerRestoreResponse;
import com.self.multi_currency_household_ledger.ledger.dto.SyncLedgerEntryRequest;
import com.self.multi_currency_household_ledger.ledger.dto.SyncLedgerEntryResponse;
import com.self.multi_currency_household_ledger.ledger.service.LedgerService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
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

    @PostMapping("/import")
    public ApiResponse<ImportLedgerEntriesResponse> importLedgerEntries(
            @CurrentMemberId UUID memberId, @Valid @RequestBody ImportLedgerEntriesRequest request) {
        ImportLedgerEntriesResponse response = ledgerService.importEntries(request, memberId);
        return ApiResponse.success(response);
    }

    @PostMapping("/sync")
    public ApiResponse<SyncLedgerEntryResponse> syncLedgerEntry(
            @CurrentMemberId UUID memberId, @Valid @RequestBody SyncLedgerEntryRequest request) {
        SyncLedgerEntryResponse response = ledgerService.sync(request, memberId);
        return ApiResponse.success(response);
    }

    @DeleteMapping("/sync/{clientEntryId}")
    public ApiResponse<Void> deleteSyncedLedgerEntry(
            @CurrentMemberId UUID memberId, @PathVariable("clientEntryId") UUID clientEntryId) {
        ledgerService.deleteSyncedEntry(clientEntryId, memberId);
        return ApiResponse.success(null);
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

    @GetMapping("/changes")
    public ApiResponse<LedgerChangesResponse> getLedgerChanges(
            @CurrentMemberId UUID memberId,
            @RequestParam(value = "cursorUpdatedAt", required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    LocalDateTime cursorUpdatedAt,
            @RequestParam(value = "cursorId", required = false) @Min(1) Long cursorId,
            @RequestParam(value = "size", defaultValue = "500") @Min(1) @Max(500) int size) {
        LedgerChangesResponse response = ledgerService.getChanges(memberId, cursorUpdatedAt, cursorId, size);
        return ApiResponse.success(response);
    }

    @GetMapping("/restore")
    public ApiResponse<LedgerRestoreResponse> restoreLedgerEntries(
            @CurrentMemberId UUID memberId,
            @RequestParam(value = "cursorDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate cursorDate,
            @RequestParam(value = "cursorId", required = false) @Min(1) Long cursorId,
            @RequestParam(value = "size", defaultValue = "500") @Min(1) @Max(500) int size) {
        LedgerRestoreResponse response = ledgerService.restore(memberId, cursorDate, cursorId, size);
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

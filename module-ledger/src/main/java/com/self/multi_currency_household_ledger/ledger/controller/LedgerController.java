package com.self.multi_currency_household_ledger.ledger.controller;

import com.self.multi_currency_household_ledger.common.dto.ApiResponse;
import com.self.multi_currency_household_ledger.ledger.dto.CreateLedgerEntryRequest;
import com.self.multi_currency_household_ledger.ledger.dto.LedgerEntryResponse;
import com.self.multi_currency_household_ledger.ledger.service.LedgerService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/ledgers")
public class LedgerController {

    private final LedgerService ledgerService;

    @PostMapping
    public ApiResponse<LedgerEntryResponse> createLedgerEntry(@Valid @RequestBody CreateLedgerEntryRequest request) {
        Long memberId = 1L; // TODO: Spring Security 적용 후 @AuthenticationPrincipal 등으로 대체
        LedgerEntryResponse response = ledgerService.create(request, memberId);
        return ApiResponse.success(response);
    }
}

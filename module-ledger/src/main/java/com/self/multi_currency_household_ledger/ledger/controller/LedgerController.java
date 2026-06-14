package com.self.multi_currency_household_ledger.ledger.controller;

import com.self.multi_currency_household_ledger.common.dto.ApiResponse;
import com.self.multi_currency_household_ledger.ledger.dto.CreateLedgerEntryRequest;
import com.self.multi_currency_household_ledger.ledger.dto.LedgerEntryResponse;
import com.self.multi_currency_household_ledger.ledger.service.LedgerService;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/ledgers")
public class LedgerController {

    private static final UUID TEMP_MEMBER_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001"); // TODO: step 2에서 @CurrentMemberId로 교체

    private final LedgerService ledgerService;

    @PostMapping
    public ApiResponse<LedgerEntryResponse> createLedgerEntry(@Valid @RequestBody CreateLedgerEntryRequest request) {
        LedgerEntryResponse response = ledgerService.create(request, TEMP_MEMBER_ID);
        return ApiResponse.success(response);
    }
}

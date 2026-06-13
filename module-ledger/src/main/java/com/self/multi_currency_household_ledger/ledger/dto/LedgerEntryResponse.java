package com.self.multi_currency_household_ledger.ledger.dto;

import com.self.multi_currency_household_ledger.exchange.domain.CurrencyCode;
import com.self.multi_currency_household_ledger.ledger.domain.LedgerEntry;
import com.self.multi_currency_household_ledger.ledger.domain.TransactionType;
import java.math.BigDecimal;
import java.time.LocalDate;

public record LedgerEntryResponse(
        Long id,
        TransactionType transactionType,
        CategoryResponse category,
        AssetResponse asset,
        BigDecimal originalAmount,
        CurrencyCode currencyCode,
        BigDecimal appliedRate,
        BigDecimal krwAmount,
        LocalDate rateBaseDate,
        LocalDate transactionDate,
        String memo) {
    public static LedgerEntryResponse from(LedgerEntry entry) {
        return new LedgerEntryResponse(
                entry.getId(),
                entry.getTransactionType(),
                CategoryResponse.from(entry.getCategory()),
                AssetResponse.from(entry.getAsset()),
                entry.getOriginalAmount(),
                entry.getCurrencyCode(),
                entry.getAppliedRate(),
                entry.getKrwAmount(),
                entry.getRateBaseDate(),
                entry.getTransactionDate(),
                entry.getMemo());
    }
}

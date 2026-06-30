package com.self.multi_currency_household_ledger.ledger.dto;

import com.self.multi_currency_household_ledger.exchange.domain.CurrencyCode;
import com.self.multi_currency_household_ledger.ledger.domain.LedgerEntry;
import com.self.multi_currency_household_ledger.ledger.domain.TransactionType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record LedgerRestoreResponse(List<RestoredLedgerEntry> entries, RestoreCursor nextCursor, boolean hasNext) {

    public static LedgerRestoreResponse from(List<LedgerEntry> entries, boolean hasNext) {
        List<RestoredLedgerEntry> restoredEntries =
                entries.stream().map(RestoredLedgerEntry::from).toList();
        RestoreCursor nextCursor = hasNext && !entries.isEmpty() ? RestoreCursor.from(entries.getLast()) : null;
        return new LedgerRestoreResponse(restoredEntries, nextCursor, hasNext);
    }

    public record RestoreCursor(LocalDate transactionDate, Long id) {

        private static RestoreCursor from(LedgerEntry entry) {
            return new RestoreCursor(entry.getTransactionDate(), entry.getId());
        }
    }

    public record RestoredLedgerEntry(
            Long id,
            UUID clientEntryId,
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

        private static RestoredLedgerEntry from(LedgerEntry entry) {
            return new RestoredLedgerEntry(
                    entry.getId(),
                    entry.getClientEntryId(),
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
}

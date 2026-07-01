package com.self.multi_currency_household_ledger.ledger.dto;

import com.self.multi_currency_household_ledger.exchange.domain.CurrencyCode;
import com.self.multi_currency_household_ledger.ledger.domain.LedgerEntry;
import com.self.multi_currency_household_ledger.ledger.domain.TransactionType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record LedgerChangesResponse(List<ChangedLedgerEntry> entries, Cursor nextCursor, boolean hasMore) {

    public static LedgerChangesResponse from(List<LedgerEntry> entries, boolean hasMore) {
        List<ChangedLedgerEntry> changedEntries =
                entries.stream().map(ChangedLedgerEntry::from).toList();
        Cursor nextCursor = entries.isEmpty() ? null : Cursor.from(entries.getLast());
        return new LedgerChangesResponse(changedEntries, nextCursor, hasMore);
    }

    public record Cursor(LocalDateTime updatedAt, Long id) {

        private static Cursor from(LedgerEntry entry) {
            return new Cursor(entry.getUpdatedAt(), entry.getId());
        }
    }

    public record ChangedLedgerEntry(
            Long id,
            UUID clientEntryId,
            LocalDateTime updatedAt,
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

        private static ChangedLedgerEntry from(LedgerEntry entry) {
            return new ChangedLedgerEntry(
                    entry.getId(),
                    entry.getClientEntryId(),
                    entry.getUpdatedAt(),
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

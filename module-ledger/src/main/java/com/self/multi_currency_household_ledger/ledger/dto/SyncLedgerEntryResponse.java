package com.self.multi_currency_household_ledger.ledger.dto;

import com.self.multi_currency_household_ledger.ledger.domain.LedgerEntry;
import java.util.UUID;

public record SyncLedgerEntryResponse(UUID clientEntryId, LedgerEntryResponse ledgerEntry) {

    public static SyncLedgerEntryResponse from(UUID clientEntryId, LedgerEntry entry) {
        return new SyncLedgerEntryResponse(clientEntryId, LedgerEntryResponse.from(entry));
    }
}

package com.self.multi_currency_household_ledger.ledger.dto;

import java.util.List;
import java.util.UUID;

public record ImportLedgerEntriesResponse(List<ImportedLedgerEntry> entries) {

    public record ImportedLedgerEntry(UUID clientEntryId, LedgerEntryResponse ledgerEntry) {}
}

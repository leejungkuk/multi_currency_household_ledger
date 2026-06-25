package com.self.multi_currency_household_ledger.ledger.dto;

import com.self.multi_currency_household_ledger.exchange.domain.CurrencyCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;

public record ImportLedgerEntriesRequest(
        @NotNull @Size(max = MAX_ENTRIES) List<@NotNull @Valid ImportLedgerEntryItem> entries) {

    public static final int MAX_ENTRIES = 1000;

    public record ImportLedgerEntryItem(
            @NotNull UUID clientEntryId,
            @NotNull @Positive @DecimalMax("99999999.00") @Digits(integer = 8, fraction = 2) BigDecimal amount,
            @NotNull CurrencyCode currencyCode,
            @NotNull Long categoryId,
            @NotNull Long assetId,
            @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate transactionDate,
            @Size(max = 255) String memo) {}
}

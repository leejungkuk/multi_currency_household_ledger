package com.self.multi_currency_household_ledger.ledger.dto;

import com.self.multi_currency_household_ledger.exchange.domain.CurrencyCode;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;

public record CreateLedgerEntryRequest(
        @NotNull @Positive BigDecimal amount,
        @NotNull CurrencyCode currencyCode,
        @NotNull Long categoryId,
        @NotNull Long assetId,
        @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate transactionDate,
        @Size(max = 255) String memo) {}

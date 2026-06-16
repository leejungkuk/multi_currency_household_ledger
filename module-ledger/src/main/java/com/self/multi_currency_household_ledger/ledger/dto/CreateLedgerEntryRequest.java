package com.self.multi_currency_household_ledger.ledger.dto;

import com.self.multi_currency_household_ledger.exchange.domain.CurrencyCode;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;

public record CreateLedgerEntryRequest(
        // 금액 8자리(≤99,999,999.00) + 소수 2자리 — 도메인 LedgerEntry 검증과 동일 상한.
        // @Digits=형식(정수 8·소수 2자리), @DecimalMax=값 상한(상호 보완).
        @NotNull @Positive @DecimalMax("99999999.00") @Digits(integer = 8, fraction = 2) BigDecimal amount,
        @NotNull CurrencyCode currencyCode,
        @NotNull Long categoryId,
        @NotNull Long assetId,
        @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate transactionDate,
        @Size(max = 255) String memo) {}

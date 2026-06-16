package com.self.multi_currency_household_ledger.ledger.dto;

import com.self.multi_currency_household_ledger.exchange.domain.CurrencyCode;
import com.self.multi_currency_household_ledger.ledger.domain.LedgerEntryRepository.CategorySubtotalProjection;
import com.self.multi_currency_household_ledger.ledger.domain.LedgerEntryRepository.CurrencySubtotalProjection;
import com.self.multi_currency_household_ledger.ledger.domain.TransactionType;
import java.math.BigDecimal;
import java.util.List;

public record LedgerReportResponse(List<CurrencySubtotal> currencySubtotals, List<CategorySubtotal> categorySubtotals) {

    public record CurrencySubtotal(
            CurrencyCode currencyCode,
            TransactionType transactionType,
            BigDecimal originalAmount,
            BigDecimal krwAmount) {

        public static CurrencySubtotal from(CurrencySubtotalProjection subtotal) {
            return new CurrencySubtotal(
                    subtotal.getCurrencyCode(),
                    subtotal.getTransactionType(),
                    subtotal.getOriginalAmount(),
                    subtotal.getKrwAmount());
        }
    }

    public record CategorySubtotal(CategoryResponse category, TransactionType transactionType, BigDecimal krwAmount) {

        public static CategorySubtotal from(CategorySubtotalProjection subtotal) {
            CategoryResponse category = new CategoryResponse(
                    subtotal.getCategoryId(),
                    subtotal.getCategoryCode(),
                    subtotal.getCategoryDisplayName(),
                    subtotal.getCategoryIcon(),
                    subtotal.getCategorySortOrder());
            return new CategorySubtotal(category, subtotal.getTransactionType(), subtotal.getKrwAmount());
        }
    }
}

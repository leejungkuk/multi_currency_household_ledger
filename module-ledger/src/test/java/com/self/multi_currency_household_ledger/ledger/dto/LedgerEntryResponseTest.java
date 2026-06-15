package com.self.multi_currency_household_ledger.ledger.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.self.multi_currency_household_ledger.exchange.domain.CurrencyCode;
import com.self.multi_currency_household_ledger.ledger.domain.TransactionType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class LedgerEntryResponseTest {

    @Test
    void test() {
        CategoryResponse category = new CategoryResponse(1L, "food", "식비", "🍔", 1);
        AssetResponse asset = new AssetResponse(1L, "card", "카드", "💳", 1);
        LedgerEntryResponse response = new LedgerEntryResponse(
                1L,
                TransactionType.EXPENSE,
                category,
                asset,
                new BigDecimal("100"),
                CurrencyCode.KRW,
                BigDecimal.ONE,
                new BigDecimal("100"),
                null,
                LocalDate.now(ZoneId.of("Asia/Seoul")),
                "memo");
        assertThat(response.id()).isEqualTo(1L);
    }
}

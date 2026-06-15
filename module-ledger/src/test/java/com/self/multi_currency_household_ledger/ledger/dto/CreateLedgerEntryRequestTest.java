package com.self.multi_currency_household_ledger.ledger.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.self.multi_currency_household_ledger.exchange.domain.CurrencyCode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class CreateLedgerEntryRequestTest {

    @Test
    void create() {
        CreateLedgerEntryRequest request = new CreateLedgerEntryRequest(
                new BigDecimal("100"), CurrencyCode.USD, 1L, 2L, LocalDate.now(ZoneId.of("Asia/Seoul")), "memo");
        assertThat(request.amount()).isEqualByComparingTo("100");
    }
}

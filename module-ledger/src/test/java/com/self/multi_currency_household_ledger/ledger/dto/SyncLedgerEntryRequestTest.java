package com.self.multi_currency_household_ledger.ledger.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.self.multi_currency_household_ledger.exchange.domain.CurrencyCode;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SyncLedgerEntryRequestTest {

    @Test
    void fields_are_validated_like_create_request_plus_client_entry_id() {
        SyncLedgerEntryRequest request = new SyncLedgerEntryRequest(null, null, null, null, null, null, "memo");

        Set<ConstraintViolation<SyncLedgerEntryRequest>> violations = validate(request);

        assertThat(violations)
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("clientEntryId", "amount", "currencyCode", "categoryId", "assetId", "transactionDate");
    }

    @Test
    void amount_must_not_exceed_eight_integer_digits() {
        SyncLedgerEntryRequest request = new SyncLedgerEntryRequest(
                UUID.fromString("10000000-0000-0000-0000-000000000001"),
                new BigDecimal("100000000.00"),
                CurrencyCode.KRW,
                1L,
                1L,
                LocalDate.of(2026, 4, 6),
                "memo");

        Set<ConstraintViolation<SyncLedgerEntryRequest>> violations = validate(request);

        assertThat(violations)
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("amount");
    }

    private Set<ConstraintViolation<SyncLedgerEntryRequest>> validate(SyncLedgerEntryRequest request) {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            return validator.validate(request);
        }
    }
}

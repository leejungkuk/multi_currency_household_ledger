package com.self.multi_currency_household_ledger.ledger.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.self.multi_currency_household_ledger.exchange.domain.CurrencyCode;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ImportLedgerEntriesRequestTest {

    @Test
    void entries_must_not_exceed_import_batch_limit() {
        ImportLedgerEntriesRequest request = new ImportLedgerEntriesRequest(IntStream.rangeClosed(0, 1000)
                .mapToObj(index -> item(UUID.fromString(String.format("10000000-0000-0000-0000-%012d", index))))
                .toList());

        Set<ConstraintViolation<ImportLedgerEntriesRequest>> violations = validate(request);

        assertThat(violations)
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("entries");
    }

    @Test
    void item_fields_are_validated() {
        ImportLedgerEntriesRequest request = new ImportLedgerEntriesRequest(Collections.singletonList(
                new ImportLedgerEntriesRequest.ImportLedgerEntryItem(null, null, null, null, null, null, "memo")));

        Set<ConstraintViolation<ImportLedgerEntriesRequest>> violations = validate(request);

        assertThat(violations)
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains(
                        "entries[0].clientEntryId",
                        "entries[0].amount",
                        "entries[0].currencyCode",
                        "entries[0].categoryId",
                        "entries[0].assetId",
                        "entries[0].transactionDate");
    }

    @Test
    void entries_must_not_contain_null_items() {
        ImportLedgerEntriesRequest request = new ImportLedgerEntriesRequest(Collections.singletonList(null));

        Set<ConstraintViolation<ImportLedgerEntriesRequest>> violations = validate(request);

        assertThat(violations)
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("entries[0].<list element>");
    }

    private Set<ConstraintViolation<ImportLedgerEntriesRequest>> validate(ImportLedgerEntriesRequest request) {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            return validator.validate(request);
        }
    }

    private ImportLedgerEntriesRequest.ImportLedgerEntryItem item(UUID clientEntryId) {
        return new ImportLedgerEntriesRequest.ImportLedgerEntryItem(
                clientEntryId, new BigDecimal("100.00"), CurrencyCode.KRW, 1L, 1L, LocalDate.of(2026, 4, 6), "memo");
    }
}

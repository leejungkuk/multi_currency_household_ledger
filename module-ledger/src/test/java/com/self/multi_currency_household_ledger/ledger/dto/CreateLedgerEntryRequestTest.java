package com.self.multi_currency_household_ledger.ledger.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.self.multi_currency_household_ledger.exchange.domain.CurrencyCode;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CreateLedgerEntryRequestTest {

    @Test
    void create() {
        CreateLedgerEntryRequest request = new CreateLedgerEntryRequest(
                new BigDecimal("100"), CurrencyCode.USD, 1L, 2L, LocalDate.now(ZoneId.of("Asia/Seoul")), "memo");
        assertThat(request.amount()).isEqualByComparingTo("100");
    }

    @Test
    void amount_must_not_exceed_eight_integer_digits() {
        CreateLedgerEntryRequest request = new CreateLedgerEntryRequest(
                new BigDecimal("100000000.00"),
                CurrencyCode.KRW,
                1L,
                2L,
                LocalDate.now(ZoneId.of("Asia/Seoul")),
                "memo");

        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            Set<ConstraintViolation<CreateLedgerEntryRequest>> violations = validator.validate(request);

            assertThat(violations)
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .contains("amount");
        }
    }
}

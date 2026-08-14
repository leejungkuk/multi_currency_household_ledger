package com.self.multi_currency_household_ledger.ledger.dto;

import com.self.multi_currency_household_ledger.ledger.domain.TransactionType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateCustomCategoryRequest(
        @NotNull TransactionType transactionType, @NotBlank @Size(max = 50) String name, @Size(max = 20) String icon) {}

package com.self.multi_currency_household_ledger.ledger.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateCustomCategoryRequest(@NotBlank @Size(max = 50) String name, @Size(max = 20) String icon) {}

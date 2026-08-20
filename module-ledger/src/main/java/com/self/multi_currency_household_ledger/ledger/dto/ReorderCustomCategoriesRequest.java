package com.self.multi_currency_household_ledger.ledger.dto;

import com.self.multi_currency_household_ledger.ledger.domain.Category;
import com.self.multi_currency_household_ledger.ledger.domain.TransactionType;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record ReorderCustomCategoriesRequest(
        @NotNull TransactionType transactionType,
        // min 은 @NotEmpty 와 중복이지만, springdoc 이 @Size 만 보고 minItems 를 적어 계약 스냅샷이 0(빈 배열 허용)이 된다.
        @NotEmpty @Size(min = 1, max = Category.CUSTOM_LIMIT) List<Long> orderedIds) {}

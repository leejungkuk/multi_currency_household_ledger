package com.self.multi_currency_household_ledger.ledger.dto;

import com.self.multi_currency_household_ledger.exchange.domain.CurrencyCode;
import com.self.multi_currency_household_ledger.ledger.domain.Budget;
import com.self.multi_currency_household_ledger.ledger.domain.BudgetApplyTo;
import com.self.multi_currency_household_ledger.ledger.domain.PaymentGroup;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * amounts 가 null 이면 끔이다. 금액 범위·자릿수·몫 규칙은 별도 에러 코드가 필요해 bean validation 이 아니라 도메인(Budget)이 검증한다 — 그래서
 * totalAmount 에는 @NotNull 도 없다(없으면 BUDGET_TOTAL_REQUIRED).
 */
public record SaveBudgetRequest(@NotNull BudgetApplyTo applyTo, @Valid BudgetAmountsRequest amounts) {

    public record BudgetAmountsRequest(
            @NotNull CurrencyCode currency,
            BigDecimal totalAmount,
            List<@NotNull @Valid PaymentGroupAmountRequest> paymentGroupAmounts,
            // 상한이 없으면 수만 개의 id 가 IN 바인드 한도를 넘어 500 이 된다.
            @Size(max = 100) List<@NotNull @Valid CategoryAmountRequest> categoryAmounts) {

        public List<Budget.GroupAmount> groupAmounts() {
            return Objects.requireNonNullElse(paymentGroupAmounts, List.<PaymentGroupAmountRequest>of()).stream()
                    .map(item -> new Budget.GroupAmount(item.paymentGroup(), item.amount()))
                    .toList();
        }

        public List<Budget.CategoryAmount> categoryAmountList() {
            return Objects.requireNonNullElse(categoryAmounts, List.<CategoryAmountRequest>of()).stream()
                    .map(item -> new Budget.CategoryAmount(item.categoryId(), item.amount()))
                    .toList();
        }

        public Set<Long> categoryIds() {
            return categoryAmountList().stream()
                    .map(Budget.CategoryAmount::categoryId)
                    .collect(Collectors.toSet());
        }
    }

    public record PaymentGroupAmountRequest(@NotNull PaymentGroup paymentGroup, @NotNull BigDecimal amount) {}

    public record CategoryAmountRequest(@NotNull Long categoryId, @NotNull BigDecimal amount) {}
}

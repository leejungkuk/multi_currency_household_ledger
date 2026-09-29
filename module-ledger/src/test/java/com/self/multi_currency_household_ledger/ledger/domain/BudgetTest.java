package com.self.multi_currency_household_ledger.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.self.multi_currency_household_ledger.common.exception.BusinessException;
import com.self.multi_currency_household_ledger.exchange.domain.CurrencyCode;
import com.self.multi_currency_household_ledger.ledger.domain.Budget.CategoryAmount;
import com.self.multi_currency_household_ledger.ledger.domain.Budget.GroupAmount;
import com.self.multi_currency_household_ledger.ledger.exception.BudgetErrorCode;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

class BudgetTest {

    private static final YearMonth SEPTEMBER = YearMonth.of(2026, 9);

    private static Budget emptyBudget(TransactionType axis) {
        return new Budget(UUID.randomUUID(), axis, BudgetKind.MONTH, SEPTEMBER, null, null, List.of());
    }

    private static BigDecimal won(String value) {
        return new BigDecimal(value);
    }

    @Test
    @DisplayName("금액 세트를 넣으면 통화·전체·몫이 바뀌고 amounts 로 읽힌다")
    void replace_amounts_sets_values() {
        Budget budget = emptyBudget(TransactionType.EXPENSE);

        budget.replaceAmounts(
                CurrencyCode.KRW,
                won("1000000"),
                List.of(new GroupAmount(PaymentGroup.CREDIT_CARD, won("600000"))),
                List.of(new CategoryAmount(3L, won("300000"))));

        assertThat(budget.getCurrencyCode()).isEqualTo(CurrencyCode.KRW);
        assertThat(budget.getTotalAmount()).isEqualByComparingTo("1000000");
        BudgetAmounts amounts = budget.amounts().orElseThrow();
        assertThat(amounts.currency()).isEqualTo(CurrencyCode.KRW);
        assertThat(amounts.total()).isEqualByComparingTo("1000000");
        assertThat(amounts.paymentGroupAmounts()).containsOnlyKeys(PaymentGroup.CREDIT_CARD);
        assertThat(amounts.paymentGroupAmounts().get(PaymentGroup.CREDIT_CARD)).isEqualByComparingTo("600000");
        assertThat(amounts.categoryAmounts()).containsOnlyKeys(3L);
        assertThat(amounts.categoryAmounts().get(3L)).isEqualByComparingTo("300000");
    }

    @Test
    @DisplayName("몫 교체는 키별 제자리 갱신이다 — 있던 키는 같은 객체의 금액만 바뀌고, 빠진 키는 지우고, 새 키만 더한다")
    void replace_amounts_updates_allocations_in_place() {
        Budget budget = emptyBudget(TransactionType.EXPENSE);
        budget.replaceAmounts(
                CurrencyCode.KRW,
                won("1000"),
                List.of(
                        new GroupAmount(PaymentGroup.CREDIT_CARD, won("100")),
                        new GroupAmount(PaymentGroup.CASH_AND_DEBIT, won("200"))),
                List.of(new CategoryAmount(3L, won("300")), new CategoryAmount(4L, won("400"))));
        BudgetAllocation creditCard = allocationOf(budget, PaymentGroup.CREDIT_CARD);
        BudgetAllocation category3 = allocationOf(budget, 3L);

        budget.replaceAmounts(
                CurrencyCode.KRW,
                won("2000"),
                List.of(
                        new GroupAmount(PaymentGroup.CREDIT_CARD, won("150")),
                        new GroupAmount(PaymentGroup.ACCOUNT_AND_OTHER, won("50"))),
                List.of(new CategoryAmount(3L, won("350")), new CategoryAmount(5L, won("500"))));

        assertThat(allocationOf(budget, PaymentGroup.CREDIT_CARD)).isSameAs(creditCard);
        assertThat(allocationOf(budget, 3L)).isSameAs(category3);
        assertThat(budget.getAllocations())
                .extracting(BudgetAllocation::getPaymentGroup, BudgetAllocation::getCategoryId, a -> a.getAmount()
                        .stripTrailingZeros()
                        .toPlainString())
                .containsExactlyInAnyOrder(
                        tuple(PaymentGroup.CREDIT_CARD, null, "150"),
                        tuple(PaymentGroup.ACCOUNT_AND_OTHER, null, "50"),
                        tuple(null, 3L, "350"),
                        tuple(null, 5L, "500"));
        assertThat(budget.getAllocations())
                .allSatisfy(a -> assertThat(a.getBudget()).isSameAs(budget));
    }

    @Test
    @DisplayName("같은 몫 세트를 다시 보내면(멱등 재전송) 객체가 그대로이고 결과가 같다")
    void replace_amounts_is_idempotent() {
        Budget budget = emptyBudget(TransactionType.EXPENSE);
        List<GroupAmount> groups = List.of(new GroupAmount(PaymentGroup.CREDIT_CARD, won("100")));
        List<CategoryAmount> categories = List.of(new CategoryAmount(3L, won("300")));
        budget.replaceAmounts(CurrencyCode.KRW, won("1000"), groups, categories);
        List<BudgetAllocation> before = List.copyOf(budget.getAllocations());

        budget.replaceAmounts(CurrencyCode.KRW, won("1000"), groups, categories);

        assertThat(budget.getAllocations()).containsExactlyInAnyOrderElementsOf(before);
        assertThat(budget.getAllocations()).hasSize(2);
    }

    @Test
    @DisplayName("몫 없이 보내면 몫이 모두 사라진다")
    void replace_amounts_with_no_allocations_clears_them() {
        Budget budget = emptyBudget(TransactionType.EXPENSE);
        budget.replaceAmounts(
                CurrencyCode.KRW,
                won("1000"),
                List.of(new GroupAmount(PaymentGroup.CREDIT_CARD, won("100"))),
                List.of(new CategoryAmount(3L, won("300"))));

        budget.replaceAmounts(CurrencyCode.USD, won("12.50"), List.of(), List.of());

        assertThat(budget.getAllocations()).isEmpty();
        assertThat(budget.getCurrencyCode()).isEqualTo(CurrencyCode.USD);
    }

    @Test
    @DisplayName("끄면 통화·전체·몫이 모두 비고 amounts 가 없다")
    void turn_off_clears_everything() {
        Budget budget = emptyBudget(TransactionType.EXPENSE);
        budget.replaceAmounts(
                CurrencyCode.KRW,
                won("1000"),
                List.of(new GroupAmount(PaymentGroup.CREDIT_CARD, won("100"))),
                List.of(new CategoryAmount(3L, won("300"))));

        budget.turnOff();

        assertThat(budget.getCurrencyCode()).isNull();
        assertThat(budget.getTotalAmount()).isNull();
        assertThat(budget.getAllocations()).isEmpty();
        assertThat(budget.amounts()).isEmpty();
    }

    @Test
    @DisplayName("경계 금액 0 과 99,999,999, 통화 자릿수 이내 소수는 받는다")
    void accepts_boundary_amounts() {
        Budget budget = emptyBudget(TransactionType.EXPENSE);

        budget.replaceAmounts(
                CurrencyCode.KRW,
                won("99999999"),
                List.of(new GroupAmount(PaymentGroup.CREDIT_CARD, won("0"))),
                List.of(new CategoryAmount(3L, won("100.00"))));
        budget.replaceAmounts(
                CurrencyCode.USD, won("99999999.00"), List.of(), List.of(new CategoryAmount(3L, won("0.01"))));

        assertThat(budget.getTotalAmount()).isEqualByComparingTo("99999999");
    }

    @Test
    @DisplayName("전체가 음수·1억·KRW 소수·USD 소수 3자리면 BUDGET_INVALID_AMOUNT")
    void rejects_invalid_total() {
        assertInvalidTotal(CurrencyCode.KRW, "-1");
        assertInvalidTotal(CurrencyCode.KRW, "100000000");
        assertInvalidTotal(CurrencyCode.USD, "99999999.01");
        assertInvalidTotal(CurrencyCode.KRW, "1000.5");
        assertInvalidTotal(CurrencyCode.JPY, "0.1");
        assertInvalidTotal(CurrencyCode.USD, "10.001");
    }

    private static void assertInvalidTotal(CurrencyCode currency, String total) {
        Budget budget = emptyBudget(TransactionType.EXPENSE);
        assertCode(
                () -> budget.replaceAmounts(currency, won(total), List.of(), List.of()),
                BudgetErrorCode.BUDGET_INVALID_AMOUNT);
    }

    @Test
    @DisplayName("몫 금액이 범위·자릿수를 벗어나면 BUDGET_INVALID_AMOUNT")
    void rejects_invalid_allocation_amount() {
        Budget budget = emptyBudget(TransactionType.EXPENSE);

        assertCode(
                () -> budget.replaceAmounts(
                        CurrencyCode.KRW,
                        won("1000"),
                        List.of(new GroupAmount(PaymentGroup.CREDIT_CARD, won("-1"))),
                        List.of()),
                BudgetErrorCode.BUDGET_INVALID_AMOUNT);
        assertCode(
                () -> budget.replaceAmounts(
                        CurrencyCode.KRW, won("1000"), List.of(), List.of(new CategoryAmount(3L, won("100000000")))),
                BudgetErrorCode.BUDGET_INVALID_AMOUNT);
        assertCode(
                () -> budget.replaceAmounts(
                        CurrencyCode.KRW, won("1000"), List.of(), List.of(new CategoryAmount(3L, won("0.5")))),
                BudgetErrorCode.BUDGET_INVALID_AMOUNT);
        assertCode(
                () -> budget.replaceAmounts(
                        CurrencyCode.USD,
                        won("1000"),
                        List.of(new GroupAmount(PaymentGroup.CREDIT_CARD, won("1.001"))),
                        List.of()),
                BudgetErrorCode.BUDGET_INVALID_AMOUNT);
    }

    @Test
    @DisplayName("몫 키가 중복되면 BUDGET_INVALID_ALLOCATION")
    void rejects_duplicate_allocation_keys() {
        Budget budget = emptyBudget(TransactionType.EXPENSE);

        assertCode(
                () -> budget.replaceAmounts(
                        CurrencyCode.KRW,
                        won("1000"),
                        List.of(
                                new GroupAmount(PaymentGroup.CREDIT_CARD, won("100")),
                                new GroupAmount(PaymentGroup.CREDIT_CARD, won("200"))),
                        List.of()),
                BudgetErrorCode.BUDGET_INVALID_ALLOCATION);
        assertCode(
                () -> budget.replaceAmounts(
                        CurrencyCode.KRW,
                        won("1000"),
                        List.of(),
                        List.of(new CategoryAmount(3L, won("100")), new CategoryAmount(3L, won("100")))),
                BudgetErrorCode.BUDGET_INVALID_ALLOCATION);
    }

    @Test
    @DisplayName("수입 축에 결제수단 몫이 있으면 BUDGET_INVALID_ALLOCATION, 카테고리 몫은 된다")
    void income_axis_rejects_payment_group_allocation() {
        Budget income = emptyBudget(TransactionType.INCOME);

        assertCode(
                () -> income.replaceAmounts(
                        CurrencyCode.KRW,
                        won("1000"),
                        List.of(new GroupAmount(PaymentGroup.CREDIT_CARD, won("100"))),
                        List.of()),
                BudgetErrorCode.BUDGET_INVALID_ALLOCATION);

        income.replaceAmounts(CurrencyCode.KRW, won("1000"), List.of(), List.of(new CategoryAmount(9L, won("100"))));
        assertThat(income.getAllocations()).hasSize(1);
    }

    @Test
    @DisplayName("전체 없이 몫만 있으면 BUDGET_TOTAL_REQUIRED")
    void rejects_missing_total() {
        Budget budget = emptyBudget(TransactionType.EXPENSE);

        assertCode(
                () -> budget.replaceAmounts(
                        CurrencyCode.KRW,
                        null,
                        List.of(new GroupAmount(PaymentGroup.CREDIT_CARD, won("100"))),
                        List.of()),
                BudgetErrorCode.BUDGET_TOTAL_REQUIRED);
    }

    @Test
    @DisplayName("검증에 걸리면 기존 값이 그대로 남는다")
    void rejected_replace_keeps_previous_state() {
        Budget budget = emptyBudget(TransactionType.EXPENSE);
        budget.replaceAmounts(
                CurrencyCode.KRW,
                won("1000"),
                List.of(new GroupAmount(PaymentGroup.CREDIT_CARD, won("100"))),
                List.of());

        assertCode(
                () -> budget.replaceAmounts(
                        CurrencyCode.USD,
                        won("5"),
                        List.of(),
                        List.of(new CategoryAmount(3L, won("1")), new CategoryAmount(3L, won("1")))),
                BudgetErrorCode.BUDGET_INVALID_ALLOCATION);

        assertThat(budget.getCurrencyCode()).isEqualTo(CurrencyCode.KRW);
        assertThat(budget.getTotalAmount()).isEqualByComparingTo("1000");
        assertThat(budget.getAllocations()).hasSize(1);
    }

    private static BudgetAllocation allocationOf(Budget budget, PaymentGroup group) {
        return budget.getAllocations().stream()
                .filter(a -> a.getPaymentGroup() == group)
                .findFirst()
                .orElseThrow();
    }

    private static BudgetAllocation allocationOf(Budget budget, Long categoryId) {
        return budget.getAllocations().stream()
                .filter(a -> categoryId.equals(a.getCategoryId()))
                .findFirst()
                .orElseThrow();
    }

    private static void assertCode(Executable call, BudgetErrorCode expected) {
        assertThatThrownBy(call::execute)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(expected.getCode());
    }
}

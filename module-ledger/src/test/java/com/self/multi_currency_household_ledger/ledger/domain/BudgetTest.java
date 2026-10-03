package com.self.multi_currency_household_ledger.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.self.multi_currency_household_ledger.common.exception.BusinessException;
import com.self.multi_currency_household_ledger.exchange.domain.CurrencyCode;
import com.self.multi_currency_household_ledger.ledger.domain.Budget.CategoryAmount;
import com.self.multi_currency_household_ledger.ledger.domain.Budget.GroupAmount;
import com.self.multi_currency_household_ledger.ledger.exception.BudgetErrorCode;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

class BudgetTest {

    private static final YearMonth SEPTEMBER = YearMonth.of(2026, 9);

    private static Budget emptyBudget() {
        return new Budget(UUID.randomUUID(), SEPTEMBER);
    }

    private static BigDecimal won(String value) {
        return new BigDecimal(value);
    }

    @Test
    @DisplayName("금액 세트를 넣으면 통화·전체·몫이 바뀌고 amounts 로 읽힌다")
    void replace_amounts_sets_values() {
        Budget budget = emptyBudget();

        budget.replaceAmounts(
                CurrencyCode.KRW,
                won("1000000"),
                List.of(new GroupAmount(PaymentGroup.CREDIT_CARD, won("600000"))),
                List.of(new CategoryAmount(3L, won("300000"))));

        assertThat(budget.getCurrencyCode()).isEqualTo(CurrencyCode.KRW);
        assertThat(budget.getTotalAmount()).isEqualByComparingTo("1000000");
        BudgetAmounts amounts = budget.amounts();
        assertThat(amounts.currency()).isEqualTo(CurrencyCode.KRW);
        assertThat(amounts.total()).isEqualByComparingTo("1000000");
        assertThat(amounts.paymentGroupAmounts()).containsOnlyKeys(PaymentGroup.CREDIT_CARD);
        assertThat(amounts.paymentGroupAmounts().get(PaymentGroup.CREDIT_CARD)).isEqualByComparingTo("600000");
        assertThat(amounts.categoryAmounts()).containsOnlyKeys(3L);
        assertThat(amounts.categoryAmounts().get(3L)).isEqualByComparingTo("300000");
    }

    @Test
    @DisplayName("몫 교체는 키별 제자리 갱신이다 — 카테고리 몫은 같은 Map 에서 있던 키는 금액만 바뀌고, 빠진 키는 지우고, 새 키만 더한다")
    void replace_amounts_updates_allocations_in_place() {
        Budget budget = emptyBudget();
        budget.replaceAmounts(
                CurrencyCode.KRW,
                won("1000"),
                List.of(
                        new GroupAmount(PaymentGroup.CREDIT_CARD, won("100")),
                        new GroupAmount(PaymentGroup.CASH_AND_DEBIT, won("200"))),
                List.of(new CategoryAmount(3L, won("300")), new CategoryAmount(4L, won("400"))));
        Map<Long, BigDecimal> categoryAmounts = budget.getCategoryAmounts();

        budget.replaceAmounts(
                CurrencyCode.KRW,
                won("2000"),
                List.of(
                        new GroupAmount(PaymentGroup.CREDIT_CARD, won("150")),
                        new GroupAmount(PaymentGroup.ACCOUNT_AND_OTHER, won("50"))),
                List.of(new CategoryAmount(3L, won("350")), new CategoryAmount(5L, won("500"))));

        // Map 을 새로 갈아 끼우면 Hibernate 는 그 예산의 카테고리 몫 행을 전부 지우고 다시 넣는다.
        assertThat(budget.getCategoryAmounts()).isSameAs(categoryAmounts);
        assertThat(shares(budget))
                .containsExactlyInAnyOrder("CREDIT_CARD=150", "ACCOUNT_AND_OTHER=50", "3=350", "5=500");
    }

    @Test
    @DisplayName("같은 몫 세트를 다시 보내면(멱등 재전송) 카테고리 몫 Map 이 그대로이고 결과가 같다")
    void replace_amounts_is_idempotent() {
        Budget budget = emptyBudget();
        List<GroupAmount> groups = List.of(new GroupAmount(PaymentGroup.CREDIT_CARD, won("100")));
        List<CategoryAmount> categories = List.of(new CategoryAmount(3L, won("300")));
        budget.replaceAmounts(CurrencyCode.KRW, won("1000"), groups, categories);
        Map<Long, BigDecimal> categoryAmounts = budget.getCategoryAmounts();
        BudgetAmounts before = budget.amounts();

        budget.replaceAmounts(CurrencyCode.KRW, won("1000"), groups, categories);

        assertThat(budget.getCategoryAmounts()).isSameAs(categoryAmounts);
        assertThat(budget.amounts()).isEqualTo(before);
        assertThat(shares(budget)).hasSize(2);
    }

    @Test
    @DisplayName("몫 없이 보내면 몫이 모두 사라진다")
    void replace_amounts_with_no_allocations_clears_them() {
        Budget budget = emptyBudget();
        budget.replaceAmounts(
                CurrencyCode.KRW,
                won("1000"),
                List.of(new GroupAmount(PaymentGroup.CREDIT_CARD, won("100"))),
                List.of(new CategoryAmount(3L, won("300"))));

        budget.replaceAmounts(CurrencyCode.USD, won("12.50"), List.of(), List.of());

        assertThat(shares(budget)).isEmpty();
        assertThat(budget.getCreditCardAmount()).isNull();
        assertThat(budget.getCurrencyCode()).isEqualTo(CurrencyCode.USD);
    }

    @Test
    @DisplayName("결제수단 몫은 그룹마다 제 컬럼에 담긴다 — 셋을 넣으면 셋이 읽히고, 다음 교체에서 하나만 넣으면 나머지 둘은 null 이 된다")
    void payment_group_amounts_round_trip_through_columns() {
        Budget budget = emptyBudget();

        budget.replaceAmounts(
                CurrencyCode.KRW,
                won("1000"),
                List.of(
                        new GroupAmount(PaymentGroup.CREDIT_CARD, won("100")),
                        new GroupAmount(PaymentGroup.CASH_AND_DEBIT, won("200")),
                        new GroupAmount(PaymentGroup.ACCOUNT_AND_OTHER, won("300"))),
                List.of());

        assertThat(budget.getCreditCardAmount()).isEqualByComparingTo("100");
        assertThat(budget.getCashAndDebitAmount()).isEqualByComparingTo("200");
        assertThat(budget.getAccountAndOtherAmount()).isEqualByComparingTo("300");
        assertThat(shares(budget))
                .containsExactlyInAnyOrder("CREDIT_CARD=100", "CASH_AND_DEBIT=200", "ACCOUNT_AND_OTHER=300");

        budget.replaceAmounts(
                CurrencyCode.KRW,
                won("1000"),
                List.of(new GroupAmount(PaymentGroup.CASH_AND_DEBIT, won("250"))),
                List.of());

        assertThat(budget.getCreditCardAmount()).isNull();
        assertThat(budget.getCashAndDebitAmount()).isEqualByComparingTo("250");
        assertThat(budget.getAccountAndOtherAmount()).isNull();
        assertThat(budget.amounts().paymentGroupAmounts()).containsOnlyKeys(PaymentGroup.CASH_AND_DEBIT);
    }

    @Test
    @DisplayName("경계 금액 0 과 99,999,999, 통화 자릿수 이내 소수는 받는다")
    void accepts_boundary_amounts() {
        Budget budget = emptyBudget();

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
        Budget budget = emptyBudget();
        assertCode(
                () -> budget.replaceAmounts(currency, won(total), List.of(), List.of()),
                BudgetErrorCode.BUDGET_INVALID_AMOUNT);
    }

    @Test
    @DisplayName("몫 금액이 범위·자릿수를 벗어나면 BUDGET_INVALID_AMOUNT")
    void rejects_invalid_allocation_amount() {
        Budget budget = emptyBudget();

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
        Budget budget = emptyBudget();

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
    @DisplayName("전체 없이 몫만 있으면 BUDGET_TOTAL_REQUIRED")
    void rejects_missing_total() {
        Budget budget = emptyBudget();

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
        Budget budget = emptyBudget();
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
        assertThat(shares(budget)).containsExactly("CREDIT_CARD=100");
    }

    @Test
    @DisplayName("카테고리 몫 합이 전체보다 크면 BUDGET_ALLOCATION_EXCEEDS_TOTAL 이고 행의 금액·몫은 그대로다")
    void rejects_category_allocations_over_total() {
        Budget budget = emptyBudget();
        budget.replaceAmounts(
                CurrencyCode.KRW,
                won("1000"),
                List.of(new GroupAmount(PaymentGroup.CREDIT_CARD, won("100"))),
                List.of(new CategoryAmount(3L, won("300"))));

        assertCode(
                () -> budget.replaceAmounts(
                        CurrencyCode.USD,
                        won("100"),
                        List.of(),
                        List.of(new CategoryAmount(3L, won("60")), new CategoryAmount(4L, won("41")))),
                BudgetErrorCode.BUDGET_ALLOCATION_EXCEEDS_TOTAL);

        assertThat(budget.getCurrencyCode()).isEqualTo(CurrencyCode.KRW);
        assertThat(budget.getTotalAmount()).isEqualByComparingTo("1000");
        assertThat(shares(budget)).containsExactlyInAnyOrder("CREDIT_CARD=100", "3=300");
    }

    @Test
    @DisplayName("결제수단 몫 합이 전체보다 크면 BUDGET_ALLOCATION_EXCEEDS_TOTAL")
    void rejects_payment_group_allocations_over_total() {
        Budget budget = emptyBudget();

        assertCode(
                () -> budget.replaceAmounts(
                        CurrencyCode.KRW,
                        won("100"),
                        List.of(
                                new GroupAmount(PaymentGroup.CREDIT_CARD, won("60")),
                                new GroupAmount(PaymentGroup.CASH_AND_DEBIT, won("41"))),
                        List.of(new CategoryAmount(3L, won("10")))),
                BudgetErrorCode.BUDGET_ALLOCATION_EXCEEDS_TOTAL);

        assertThat(budget.getTotalAmount()).isNull();
        assertThat(shares(budget)).isEmpty();
    }

    @Test
    @DisplayName("몫 합이 전체와 같으면 받는다 — 결제수단·카테고리 각각, 스케일이 달라도(100 = 60.00 + 40)")
    void accepts_allocations_equal_to_total() {
        Budget budget = emptyBudget();

        budget.replaceAmounts(
                CurrencyCode.KRW,
                won("100"),
                List.of(
                        new GroupAmount(PaymentGroup.CREDIT_CARD, won("60.00")),
                        new GroupAmount(PaymentGroup.CASH_AND_DEBIT, won("40"))),
                List.of(new CategoryAmount(3L, won("100.00"))));

        assertThat(budget.getTotalAmount()).isEqualByComparingTo("100");
        assertThat(shares(budget)).hasSize(3);
    }

    @Test
    @DisplayName("통화의 최소 단위 하나만 넘어도 거절한다 — USD 10.00 에 10.01, KRW 100 에 101")
    void rejects_allocation_over_total_by_smallest_unit() {
        Budget budget = emptyBudget();

        assertCode(
                () -> budget.replaceAmounts(
                        CurrencyCode.USD, won("10.00"), List.of(), List.of(new CategoryAmount(3L, won("10.01")))),
                BudgetErrorCode.BUDGET_ALLOCATION_EXCEEDS_TOTAL);
        assertCode(
                () -> budget.replaceAmounts(
                        CurrencyCode.KRW,
                        won("100"),
                        List.of(new GroupAmount(PaymentGroup.CREDIT_CARD, won("101"))),
                        List.of()),
                BudgetErrorCode.BUDGET_ALLOCATION_EXCEEDS_TOTAL);
        assertThat(shares(budget)).isEmpty();
    }

    @Test
    @DisplayName("몫 카테고리는 지출 카테고리여야 한다 — 지출이면 통과, 수입이 섞이면 BUDGET_INVALID_ALLOCATION")
    void allocatable_categories_must_be_expense() {
        Budget budget = emptyBudget();
        Category expenseCategory = Category.custom(UUID.randomUUID(), TransactionType.EXPENSE, "반려견", "🐶");
        Category incomeCategory = Category.custom(UUID.randomUUID(), TransactionType.INCOME, "부수입", "💰");

        budget.requireAllocatable(List.of(expenseCategory));

        assertCode(
                () -> budget.requireAllocatable(List.of(expenseCategory, incomeCategory)),
                BudgetErrorCode.BUDGET_INVALID_ALLOCATION);
        assertCode(() -> budget.requireAllocatable(List.of(incomeCategory)), BudgetErrorCode.BUDGET_INVALID_ALLOCATION);
    }

    @Test
    @DisplayName("몫 카테고리 id 는 카테고리 몫만 담는다 — 결제수단 몫은 빠지고, 몫이 없으면 비어 있다")
    void allocated_category_ids_lists_category_allocations_only() {
        Budget budget = emptyBudget();
        budget.replaceAmounts(
                CurrencyCode.KRW,
                won("1000"),
                List.of(new GroupAmount(PaymentGroup.CREDIT_CARD, won("600"))),
                List.of(new CategoryAmount(3L, won("300")), new CategoryAmount(4L, won("100"))));

        assertThat(budget.allocatedCategoryIds()).containsExactlyInAnyOrder(3L, 4L);
        assertThat(emptyBudget().allocatedCategoryIds()).isEmpty();
    }

    /** 몫을 "키=금액" 으로 편다 — 결제수단 몫은 그룹 이름, 카테고리 몫은 카테고리 id 가 키다. */
    private static List<String> shares(Budget budget) {
        BudgetAmounts amounts = budget.amounts();
        List<String> shares = new ArrayList<>();
        amounts.paymentGroupAmounts()
                .forEach((group, amount) ->
                        shares.add(group + "=" + amount.stripTrailingZeros().toPlainString()));
        amounts.categoryAmounts()
                .forEach((categoryId, amount) -> shares.add(
                        categoryId + "=" + amount.stripTrailingZeros().toPlainString()));
        return shares;
    }

    private static void assertCode(Executable call, BudgetErrorCode expected) {
        assertThatThrownBy(call::execute)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(expected.getCode());
    }
}

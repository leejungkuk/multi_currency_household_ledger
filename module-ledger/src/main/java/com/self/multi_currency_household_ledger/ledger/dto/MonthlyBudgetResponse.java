package com.self.multi_currency_household_ledger.ledger.dto;

import com.self.multi_currency_household_ledger.exchange.domain.CurrencyCode;
import com.self.multi_currency_household_ledger.ledger.domain.BudgetEvaluation;
import com.self.multi_currency_household_ledger.ledger.domain.BudgetLine;
import com.self.multi_currency_household_ledger.ledger.domain.BudgetStatus;
import com.self.multi_currency_household_ledger.ledger.domain.Category;
import com.self.multi_currency_household_ledger.ledger.domain.PaymentGroup;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;

/**
 * 한 달의 예산. 미설정(NOT_SET)이면 금액 필드는 모두 null 이고 배열은 비어 있다. 있으면 paymentGroups 는 항상 3개, categories 는 몫이 있는
 * 카테고리만 담는다. remainingDaysIncludingToday 는 요청한 달이 이번 달일 때만 있다(예산이 없어도 준다).
 */
public record MonthlyBudgetResponse(
        int year,
        int month,
        int currentYear,
        int currentMonth,
        Integer remainingDaysIncludingToday,
        boolean hasAnyBudget,
        BudgetStatus status,
        CurrencyCode currency,
        BudgetLine total,
        List<BudgetPaymentGroupLine> paymentGroups,
        List<BudgetCategoryLine> categories,
        BigDecimal otherCategoriesActualAmount,
        BigDecimal paymentGroupUnallocatedAmount,
        boolean paymentGroupAllocationExceeded,
        BigDecimal categoryUnallocatedAmount,
        boolean categoryAllocationExceeded,
        int missingRateCount,
        BudgetEvaluation.DailyAllowance dailyAllowance) {

    public static MonthlyBudgetResponse notSet(
            YearMonth month, YearMonth current, Integer remainingDaysIncludingToday, boolean hasAnyBudget) {
        return new MonthlyBudgetResponse(
                month.getYear(),
                month.getMonthValue(),
                current.getYear(),
                current.getMonthValue(),
                remainingDaysIncludingToday,
                hasAnyBudget,
                BudgetStatus.NOT_SET,
                null,
                null,
                List.of(),
                List.of(),
                null,
                null,
                false,
                null,
                false,
                0,
                null);
    }

    /** 예산이 있는 달. categories 는 몫 카테고리 후보(표시 순서대로)이고, 몫이 있는 것만 담는다. */
    public static MonthlyBudgetResponse of(
            YearMonth month,
            YearMonth current,
            Integer remainingDaysIncludingToday,
            CurrencyCode currency,
            BudgetEvaluation evaluation,
            List<Category> categories) {
        List<BudgetPaymentGroupLine> paymentGroups = evaluation.paymentGroups().entrySet().stream()
                .map(entry -> BudgetPaymentGroupLine.of(entry.getKey(), entry.getValue()))
                .toList();
        List<BudgetCategoryLine> categoryLines = categories.stream()
                .filter(category -> evaluation.categories().containsKey(category.getId()))
                .map(category ->
                        BudgetCategoryLine.of(category, evaluation.categories().get(category.getId())))
                .toList();
        return new MonthlyBudgetResponse(
                month.getYear(),
                month.getMonthValue(),
                current.getYear(),
                current.getMonthValue(),
                remainingDaysIncludingToday,
                true,
                evaluation.total().status(),
                currency,
                evaluation.total(),
                paymentGroups,
                categoryLines,
                evaluation.otherCategoriesActualAmount(),
                evaluation.paymentGroupUnallocatedAmount(),
                evaluation.paymentGroupAllocationExceeded(),
                evaluation.categoryUnallocatedAmount(),
                evaluation.categoryAllocationExceeded(),
                evaluation.missingRateCount(),
                evaluation.dailyAllowance());
    }

    /** 결제수단 그룹 한 줄. 몫이 없으면 actualAmount 만 있다. */
    public record BudgetPaymentGroupLine(
            PaymentGroup paymentGroup,
            BigDecimal budgetAmount,
            BigDecimal actualAmount,
            BudgetStatus status,
            Integer percent,
            BigDecimal remainingAmount,
            BigDecimal overAmount) {

        static BudgetPaymentGroupLine of(PaymentGroup paymentGroup, BudgetLine line) {
            return new BudgetPaymentGroupLine(
                    paymentGroup,
                    line.budgetAmount(),
                    line.actualAmount(),
                    line.status(),
                    line.percent(),
                    line.remainingAmount(),
                    line.overAmount());
        }
    }

    /** 카테고리 몫 한 줄. 카테고리를 지워도 몫은 남으므로 deleted 인 줄은 어느 달에나 나올 수 있다. */
    public record BudgetCategoryLine(
            CategoryResponse category,
            boolean deleted,
            BigDecimal budgetAmount,
            BigDecimal actualAmount,
            BudgetStatus status,
            Integer percent,
            BigDecimal remainingAmount,
            BigDecimal overAmount) {

        static BudgetCategoryLine of(Category category, BudgetLine line) {
            return new BudgetCategoryLine(
                    CategoryResponse.from(category),
                    !category.isActive(),
                    line.budgetAmount(),
                    line.actualAmount(),
                    line.status(),
                    line.percent(),
                    line.remainingAmount(),
                    line.overAmount());
        }
    }
}

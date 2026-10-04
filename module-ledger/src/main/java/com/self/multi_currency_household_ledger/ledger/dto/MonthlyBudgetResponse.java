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
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 한 달의 예산. 미설정(NOT_SET)이면 금액 필드는 모두 null 이고 배열은 deletedCategoriesWithSpending 말고는 비어 있다. 있으면 paymentGroups 는 항상 3개, categories 는 몫이 있는
 * 카테고리만 저장된 줄 순서(PUT categoryAmounts 배열 순서)로 담고, otherCategories 는 몫이 없는 카테고리를 묶은 한 줄이다(몫 = 전체 − 카테고리 몫 합). remainingDaysIncludingToday 는 요청한 달이 이번 달일 때만 있다(예산이 없어도 준다).
 * deletedCategoriesWithSpending 은 그 달에 이 회원의 지출 거래가 1건 이상 있는 삭제된 지출 카테고리를 몫 유무와 무관하게 카테고리 정렬값·id 순으로 담는다(예산이 없어도 준다) — PUT categoryAmounts 에 넣으면 저장이 받는다.
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
        BudgetLine otherCategories,
        int missingRateCount,
        BudgetEvaluation.DailyAllowance dailyAllowance,
        List<CategoryResponse> deletedCategoriesWithSpending) {

    public static MonthlyBudgetResponse notSet(
            YearMonth month,
            YearMonth current,
            Integer remainingDaysIncludingToday,
            boolean hasAnyBudget,
            List<Category> deletedCategoriesWithSpending) {
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
                0,
                null,
                toResponses(deletedCategoriesWithSpending));
    }

    /**
     * 예산이 있는 달. categories 줄은 evaluation 의 카테고리 몫 순서 = 저장된 줄 순서(PUT categoryAmounts 배열 순서)다. categories 는 몫
     * 카테고리 후보(순서 무관)이고, 그 안에 없는 몫 id 는 줄을 만들지 않는다.
     */
    public static MonthlyBudgetResponse of(
            YearMonth month,
            YearMonth current,
            Integer remainingDaysIncludingToday,
            CurrencyCode currency,
            BudgetEvaluation evaluation,
            List<Category> categories,
            List<Category> deletedCategoriesWithSpending) {
        List<BudgetPaymentGroupLine> paymentGroups = evaluation.paymentGroups().entrySet().stream()
                .map(entry -> BudgetPaymentGroupLine.of(entry.getKey(), entry.getValue()))
                .toList();
        Map<Long, Category> categoryById =
                categories.stream().collect(Collectors.toMap(Category::getId, Function.identity()));
        List<BudgetCategoryLine> categoryLines = evaluation.categories().entrySet().stream()
                .filter(entry -> categoryById.containsKey(entry.getKey()))
                .map(entry -> BudgetCategoryLine.of(categoryById.get(entry.getKey()), entry.getValue()))
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
                evaluation.otherCategories(),
                evaluation.missingRateCount(),
                evaluation.dailyAllowance(),
                toResponses(deletedCategoriesWithSpending));
    }

    private static List<CategoryResponse> toResponses(List<Category> categories) {
        return categories.stream().map(CategoryResponse::from).toList();
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

package com.self.multi_currency_household_ledger.ledger.dto;

import com.self.multi_currency_household_ledger.exchange.domain.CurrencyCode;
import com.self.multi_currency_household_ledger.ledger.domain.BudgetEvaluation;
import com.self.multi_currency_household_ledger.ledger.domain.BudgetLine;
import com.self.multi_currency_household_ledger.ledger.domain.BudgetResolution;
import com.self.multi_currency_household_ledger.ledger.domain.BudgetSource;
import com.self.multi_currency_household_ledger.ledger.domain.BudgetStatus;
import com.self.multi_currency_household_ledger.ledger.domain.Category;
import com.self.multi_currency_household_ledger.ledger.domain.PaymentGroup;
import java.math.BigDecimal;
import java.util.List;

/**
 * 한 축의 예산. 금액이 없는 상태(NOT_SET·OFF)면 금액 필드는 모두 null 이고 배열은 비어 있다. paymentGroups 는 지출 축에 금액이 있으면 항상 3개,
 * categories 는 몫이 있는 카테고리만 담는다.
 */
public record BudgetAxisResponse(
        BudgetSource source,
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

    /** 금액 세트가 없는 축. */
    public static BudgetAxisResponse withoutAmounts(BudgetSource source) {
        BudgetStatus status = source == BudgetSource.NOT_SET ? BudgetStatus.NOT_SET : BudgetStatus.OFF;
        return new BudgetAxisResponse(
                source, status, null, null, List.of(), List.of(), null, null, false, null, false, 0, null);
    }

    /** 금액 세트가 있는 축. categories 는 몫 카테고리 후보(표시 순서대로)이고, 이 축에 몫이 있는 것만 담는다. */
    public static BudgetAxisResponse of(
            BudgetResolution resolution, BudgetEvaluation evaluation, List<Category> categories) {
        List<BudgetPaymentGroupLine> paymentGroups = evaluation.paymentGroups().entrySet().stream()
                .map(entry -> BudgetPaymentGroupLine.of(entry.getKey(), entry.getValue()))
                .toList();
        List<BudgetCategoryLine> categoryLines = categories.stream()
                .filter(category -> evaluation.categories().containsKey(category.getId()))
                .map(category ->
                        BudgetCategoryLine.of(category, evaluation.categories().get(category.getId())))
                .toList();
        return new BudgetAxisResponse(
                resolution.source(),
                evaluation.total().status(),
                resolution.amounts().currency(),
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

    /** 카테고리 몫 한 줄. deleted 인 줄은 지난 달에만 나온다(삭제 시 이번 달 이후 몫은 지운다). */
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

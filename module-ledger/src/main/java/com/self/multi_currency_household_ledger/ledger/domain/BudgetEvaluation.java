package com.self.multi_currency_household_ledger.ledger.domain;

import com.self.multi_currency_household_ledger.exchange.domain.CurrencyCode;
import com.self.multi_currency_household_ledger.exchange.domain.TtsTimeline;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 그 달의 금액 세트와 지출 거래로 계산한 예산 값(MonthlyBudgetResponse 의 금액 부분). 규칙은 요구사항 §3 이다.
 *
 * <ul>
 *   <li>거래마다 예산 통화로 한 번 환산한다. 같은 통화면 originalAmount, KRW 예산이면 krwAmount, 그 밖엔 krw × unit ÷ tts 를 scale 10
 *       HALF_UP 으로 한 번 나눈다. tts 는 거래일 이하 가장 최근 값이고 폴백이 없다.
 *   <li>환율이 없는 거래는 모든 합계에서 빼고 missingRateCount 로 센다.
 *   <li>합계·비교는 scale 10 값으로 하고, 표시값만 통화 자릿수에서 내림·올림한다.
 * </ul>
 */
public record BudgetEvaluation(
        BudgetLine total,
        Map<PaymentGroup, BudgetLine> paymentGroups,
        Map<Long, BudgetLine> categories,
        BigDecimal otherCategoriesActualAmount,
        BigDecimal paymentGroupUnallocatedAmount,
        boolean paymentGroupAllocationExceeded,
        BigDecimal categoryUnallocatedAmount,
        boolean categoryAllocationExceeded,
        int missingRateCount,
        DailyAllowance dailyAllowance) {

    private static final int CONVERSION_SCALE = 10;

    /** 초과면 amount 가 null, exceeded 가 true 다. */
    public record DailyAllowance(BigDecimal amount, boolean exceeded) {}

    /**
     * 그 달의 예산 값을 계산한다.
     *
     * @param timeline 예산 통화의 tts. KRW 예산이면 쓰지 않으므로 null 이어도 된다.
     * @param remainingDaysIncludingToday 이번 달이면 오늘 포함 남은 일수, 아니면 null(하루 권장액 없음).
     */
    public static BudgetEvaluation evaluate(
            BudgetAmounts amounts,
            List<BudgetTransaction> transactions,
            TtsTimeline timeline,
            Integer remainingDaysIncludingToday) {
        CurrencyCode currency = amounts.currency();
        int digits = currency.fractionDigits();

        BigDecimal totalActual = BigDecimal.ZERO;
        BigDecimal otherActual = BigDecimal.ZERO;
        Map<PaymentGroup, BigDecimal> groupActual = new EnumMap<>(PaymentGroup.class);
        Map<Long, BigDecimal> categoryActual = new LinkedHashMap<>();
        int missingRateCount = 0;
        for (BudgetTransaction transaction : transactions) {
            Optional<BigDecimal> converted = convert(transaction, currency, timeline);
            if (converted.isEmpty()) {
                missingRateCount++;
                continue;
            }
            BigDecimal value = converted.get();
            totalActual = totalActual.add(value);
            groupActual.merge(PaymentGroup.of(transaction.assetId()), value, BigDecimal::add);
            if (amounts.categoryAmounts().containsKey(transaction.categoryId())) {
                categoryActual.merge(transaction.categoryId(), value, BigDecimal::add);
            } else {
                otherActual = otherActual.add(value);
            }
        }

        BudgetLine total = BudgetLine.of(amounts.total(), totalActual, digits);

        Map<PaymentGroup, BudgetLine> groups = new EnumMap<>(PaymentGroup.class);
        for (PaymentGroup group : PaymentGroup.values()) {
            BigDecimal actual = groupActual.getOrDefault(group, BigDecimal.ZERO);
            BigDecimal budget = amounts.paymentGroupAmounts().get(group);
            groups.put(
                    group,
                    budget == null ? BudgetLine.actualOnly(actual, digits) : BudgetLine.of(budget, actual, digits));
        }

        Map<Long, BudgetLine> categories = new LinkedHashMap<>();
        amounts.categoryAmounts()
                .forEach((categoryId, budget) -> categories.put(
                        categoryId,
                        BudgetLine.of(budget, categoryActual.getOrDefault(categoryId, BigDecimal.ZERO), digits)));

        BigDecimal groupUnallocated = unallocated(amounts.total(), amounts.paymentGroupAmounts(), digits);
        BigDecimal categoryUnallocated = unallocated(amounts.total(), amounts.categoryAmounts(), digits);

        return new BudgetEvaluation(
                total,
                Collections.unmodifiableMap(groups),
                Collections.unmodifiableMap(categories),
                BudgetLine.floor(otherActual, digits),
                groupUnallocated,
                groupUnallocated == null,
                categoryUnallocated,
                categoryUnallocated == null,
                missingRateCount,
                dailyAllowance(amounts.total(), totalActual, total, remainingDaysIncludingToday, digits));
    }

    private static Optional<BigDecimal> convert(
            BudgetTransaction transaction, CurrencyCode currency, TtsTimeline timeline) {
        if (transaction.currency() == currency) {
            return Optional.of(transaction.originalAmount());
        }
        if (currency.isBase()) {
            return Optional.of(transaction.krwAmount());
        }
        return timeline.onOrBefore(transaction.transactionDate()).map(tts -> transaction
                .krwAmount()
                .multiply(BigDecimal.valueOf(currency.getUnit()))
                .divide(tts, CONVERSION_SCALE, RoundingMode.HALF_UP));
    }

    /** 전체 − 몫 합계를 내림. 몫 합계가 전체를 넘으면 null. */
    private static BigDecimal unallocated(BigDecimal total, Map<?, BigDecimal> shares, int digits) {
        BigDecimal remaining = shares.values().stream().reduce(total, BigDecimal::subtract);
        return remaining.signum() < 0 ? null : BudgetLine.floor(remaining, digits);
    }

    private static DailyAllowance dailyAllowance(
            BigDecimal budget, BigDecimal actual, BudgetLine total, Integer remainingDaysIncludingToday, int digits) {
        if (remainingDaysIncludingToday == null) {
            return null;
        }
        if (total.status() == BudgetStatus.EXCEEDED) {
            return new DailyAllowance(null, true);
        }
        BigDecimal amount = budget.subtract(actual)
                .divide(BigDecimal.valueOf(remainingDaysIncludingToday), digits, RoundingMode.FLOOR);
        return new DailyAllowance(amount, false);
    }
}

package com.self.multi_currency_household_ledger.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.self.multi_currency_household_ledger.exchange.domain.CurrencyCode;
import com.self.multi_currency_household_ledger.exchange.domain.ExchangeRate;
import com.self.multi_currency_household_ledger.exchange.domain.TtsTimeline;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class BudgetEvaluationTest {

    private static final LocalDate SEP_10 = LocalDate.of(2026, 9, 10);
    private static final long FOOD = 10L;
    private static final long CAFE = 20L;
    private static final long CARD = 1L;

    private static BigDecimal amount(String value) {
        return new BigDecimal(value);
    }

    private static BudgetAmounts krwBudget(String total) {
        return new BudgetAmounts(CurrencyCode.KRW, amount(total), Map.of(), Map.of());
    }

    private static BudgetTransaction krw(String krwAmount) {
        return new BudgetTransaction(CurrencyCode.KRW, amount(krwAmount), amount(krwAmount), SEP_10, FOOD, CARD);
    }

    private static BudgetEvaluation expense(BudgetAmounts amounts, List<BudgetTransaction> transactions) {
        return BudgetEvaluation.evaluate(amounts, transactions, null, null);
    }

    @Nested
    @DisplayName("상태·표시값 — 요구사항 §3 표의 경계")
    class Status {

        @Test
        @DisplayName("79.6% 는 percent 79, IN_PROGRESS")
        void below_eighty_is_in_progress() {
            BudgetLine total = expense(krwBudget("1000"), List.of(krw("796"))).total();

            assertThat(total.status()).isEqualTo(BudgetStatus.IN_PROGRESS);
            assertThat(total.percent()).isEqualTo(79);
            assertThat(total.budgetAmount()).isEqualByComparingTo("1000");
            assertThat(total.actualAmount()).isEqualByComparingTo("796");
            assertThat(total.remainingAmount()).isEqualByComparingTo("204");
            assertThat(total.overAmount()).isNull();
        }

        @Test
        @DisplayName("정확히 80% 는 NEAR_LIMIT")
        void eighty_is_near_limit() {
            BudgetLine total = expense(krwBudget("1000"), List.of(krw("800"))).total();

            assertThat(total.status()).isEqualTo(BudgetStatus.NEAR_LIMIT);
            assertThat(total.percent()).isEqualTo(80);
            assertThat(total.remainingAmount()).isEqualByComparingTo("200");
        }

        @Test
        @DisplayName("NEAR_LIMIT 인데 남은 금액 표시값이 0 일 수 있다 — 예산 1,000원, 실제 999.7원")
        void near_limit_with_zero_remaining_display() {
            BudgetLine total =
                    expense(krwBudget("1000"), List.of(krw("999.70"))).total();

            assertThat(total.status()).isEqualTo(BudgetStatus.NEAR_LIMIT);
            assertThat(total.percent()).isEqualTo(99);
            assertThat(total.actualAmount()).isEqualByComparingTo("999");
            assertThat(total.remainingAmount()).isEqualByComparingTo("0");
            assertThat(total.overAmount()).isNull();
        }

        @Test
        @DisplayName("정확히 100% 는 REACHED, percent 100, 남은 0, 하루 권장액 0")
        void exactly_hundred_is_reached() {
            BudgetEvaluation evaluation =
                    BudgetEvaluation.evaluate(krwBudget("1000"), List.of(krw("600"), krw("400")), null, 5);

            BudgetLine total = evaluation.total();
            assertThat(total.status()).isEqualTo(BudgetStatus.REACHED);
            assertThat(total.percent()).isEqualTo(100);
            assertThat(total.remainingAmount()).isEqualByComparingTo("0");
            assertThat(total.overAmount()).isNull();
            assertThat(evaluation.dailyAllowance().amount()).isEqualByComparingTo("0");
            assertThat(evaluation.dailyAllowance().exceeded()).isFalse();
        }

        @Test
        @DisplayName("초과는 EXCEEDED, percent·남은 금액 null, 넘은 금액은 통화 자릿수에서 올림, 하루 권장액은 초과 표시")
        void over_hundred_is_exceeded() {
            BudgetEvaluation evaluation =
                    BudgetEvaluation.evaluate(krwBudget("1000"), List.of(krw("1000.40")), null, 3);

            BudgetLine total = evaluation.total();
            assertThat(total.status()).isEqualTo(BudgetStatus.EXCEEDED);
            assertThat(total.percent()).isNull();
            assertThat(total.remainingAmount()).isNull();
            assertThat(total.overAmount()).isEqualByComparingTo("1");
            assertThat(total.actualAmount()).isEqualByComparingTo("1000");
            assertThat(evaluation.dailyAllowance().amount()).isNull();
            assertThat(evaluation.dailyAllowance().exceeded()).isTrue();
        }

        @Test
        @DisplayName("USD 초과 금액은 센트에서 올린다")
        void over_amount_rounds_up_at_currency_digits() {
            BudgetAmounts usd = new BudgetAmounts(CurrencyCode.USD, amount("10.00"), Map.of(), Map.of());
            TtsTimeline timeline = TtsTimeline.of(List.of(ExchangeRate.of(CurrencyCode.USD, amount("3"), SEP_10)));

            // 31 KRW ÷ 3 = 10.3333333333 USD
            BudgetLine total = BudgetEvaluation.evaluate(usd, List.of(krw("31")), timeline, null)
                    .total();

            assertThat(total.status()).isEqualTo(BudgetStatus.EXCEEDED);
            assertThat(total.actualAmount()).isEqualByComparingTo("10.33");
            assertThat(total.overAmount()).isEqualByComparingTo("0.34");
        }

        @Test
        @DisplayName("실제 0 은 NONE, percent 0")
        void zero_actual_is_none() {
            BudgetLine total = expense(krwBudget("1000"), List.of()).total();

            assertThat(total.status()).isEqualTo(BudgetStatus.NONE);
            assertThat(total.percent()).isZero();
            assertThat(total.actualAmount()).isEqualByComparingTo("0");
            assertThat(total.remainingAmount()).isEqualByComparingTo("1000");
        }

        @Test
        @DisplayName("0원 예산 — 실제 0 이면 NONE, 0 보다 크면 EXCEEDED, 둘 다 percent null")
        void zero_budget() {
            BudgetLine none = expense(krwBudget("0"), List.of()).total();
            assertThat(none.status()).isEqualTo(BudgetStatus.NONE);
            assertThat(none.percent()).isNull();
            assertThat(none.remainingAmount()).isEqualByComparingTo("0");
            assertThat(none.overAmount()).isNull();

            BudgetLine exceeded = expense(krwBudget("0"), List.of(krw("1"))).total();
            assertThat(exceeded.status()).isEqualTo(BudgetStatus.EXCEEDED);
            assertThat(exceeded.percent()).isNull();
            assertThat(exceeded.remainingAmount()).isNull();
            assertThat(exceeded.overAmount()).isEqualByComparingTo("1");
        }
    }

    @Nested
    @DisplayName("환산 — 거래마다 한 번, scale 10 HALF_UP")
    class Conversion {

        private final TtsTimeline usdFromSep1 =
                TtsTimeline.of(List.of(ExchangeRate.of(CurrencyCode.USD, amount("1350"), LocalDate.of(2026, 9, 1))));

        private BudgetAmounts usdBudget(String total) {
            return new BudgetAmounts(CurrencyCode.USD, amount(total), Map.of(), Map.of());
        }

        private BudgetTransaction tx(CurrencyCode currency, String original, String krwAmount, LocalDate date) {
            return new BudgetTransaction(currency, amount(original), amount(krwAmount), date, FOOD, CARD);
        }

        @Test
        @DisplayName("거래 통화 = 예산 통화면 originalAmount 를 쓴다(환율 없어도 된다)")
        void same_currency_uses_original_amount() {
            BudgetEvaluation evaluation = BudgetEvaluation.evaluate(
                    usdBudget("100.00"),
                    List.of(tx(CurrencyCode.USD, "12.34", "16999.00", LocalDate.of(2026, 8, 1))),
                    usdFromSep1,
                    null);

            assertThat(evaluation.total().actualAmount()).isEqualByComparingTo("12.34");
            assertThat(evaluation.missingRateCount()).isZero();
        }

        @Test
        @DisplayName("KRW 예산이면 외화 거래도 krwAmount 를 쓴다")
        void krw_budget_uses_krw_amount() {
            BudgetEvaluation evaluation =
                    expense(krwBudget("100000"), List.of(tx(CurrencyCode.USD, "10.00", "13500.00", SEP_10)));

            assertThat(evaluation.total().actualAmount()).isEqualByComparingTo("13500");
        }

        @Test
        @DisplayName("외화 예산 + KRW 거래는 krw × unit ÷ tts")
        void foreign_budget_with_krw_transaction() {
            BudgetEvaluation evaluation = BudgetEvaluation.evaluate(
                    usdBudget("100.00"), List.of(tx(CurrencyCode.KRW, "13500", "13500", SEP_10)), usdFromSep1, null);

            assertThat(evaluation.total().actualAmount()).isEqualByComparingTo("10.00");
        }

        @Test
        @DisplayName("외화 예산 + 다른 외화 거래는 그 거래의 krwAmount 를 예산 통화 tts 로 나눈다")
        void foreign_budget_with_other_foreign_transaction() {
            BudgetEvaluation evaluation = BudgetEvaluation.evaluate(
                    usdBudget("100.00"), List.of(tx(CurrencyCode.EUR, "10.00", "15000.00", SEP_10)), usdFromSep1, null);

            // 15000 ÷ 1350 = 11.1111111111 → 표시 내림 11.11
            assertThat(evaluation.total().actualAmount()).isEqualByComparingTo("11.11");
            assertThat(evaluation.total().remainingAmount()).isEqualByComparingTo("88.88");
        }

        @Test
        @DisplayName("JPY 는 unit 100 — tts 900 이면 9,000원은 1,000엔")
        void jpy_uses_unit_hundred() {
            TtsTimeline jpy =
                    TtsTimeline.of(List.of(ExchangeRate.of(CurrencyCode.JPY, amount("900"), LocalDate.of(2026, 9, 1))));
            BudgetAmounts budget = new BudgetAmounts(CurrencyCode.JPY, amount("2000"), Map.of(), Map.of());

            BudgetEvaluation evaluation =
                    BudgetEvaluation.evaluate(budget, List.of(tx(CurrencyCode.KRW, "9000", "9000", SEP_10)), jpy, null);

            assertThat(evaluation.total().actualAmount()).isEqualByComparingTo("1000");
            assertThat(evaluation.total().percent()).isEqualTo(50);
        }

        @Test
        @DisplayName("거래일 이하 환율이 없으면 모든 합계에서 빼고 missingRateCount 로 센다")
        void missing_rate_is_excluded_from_every_sum() {
            BudgetAmounts budget = new BudgetAmounts(
                    CurrencyCode.USD,
                    amount("100.00"),
                    Map.of(PaymentGroup.CREDIT_CARD, amount("50.00")),
                    Map.of(FOOD, amount("50.00")));
            BudgetTransaction beforeAnyRate = new BudgetTransaction(
                    CurrencyCode.KRW, amount("13500"), amount("13500"), LocalDate.of(2026, 8, 31), FOOD, CARD);
            BudgetTransaction otherCategoryBeforeRate = new BudgetTransaction(
                    CurrencyCode.KRW, amount("13500"), amount("13500"), LocalDate.of(2026, 8, 31), CAFE, CARD);
            BudgetTransaction withRate =
                    new BudgetTransaction(CurrencyCode.KRW, amount("2700"), amount("2700"), SEP_10, FOOD, CARD);

            BudgetEvaluation evaluation = BudgetEvaluation.evaluate(
                    budget, List.of(beforeAnyRate, otherCategoryBeforeRate, withRate), usdFromSep1, null);

            assertThat(evaluation.missingRateCount()).isEqualTo(2);
            assertThat(evaluation.total().actualAmount()).isEqualByComparingTo("2.00");
            assertThat(evaluation.paymentGroups().get(PaymentGroup.CREDIT_CARD).actualAmount())
                    .isEqualByComparingTo("2.00");
            assertThat(evaluation.categories().get(FOOD).actualAmount()).isEqualByComparingTo("2.00");
            assertThat(evaluation.otherCategories().actualAmount()).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("나눗셈은 거래마다 한 번이다 — 1원씩 세 번(0.9999999999)은 3원 한 번(1.0000000000)과 판정이 다르다")
        void divides_per_transaction_not_after_summing() {
            TtsTimeline three =
                    TtsTimeline.of(List.of(ExchangeRate.of(CurrencyCode.USD, amount("3"), LocalDate.of(2026, 9, 1))));
            BudgetTransaction oneWon = tx(CurrencyCode.KRW, "1", "1", SEP_10);

            BudgetLine perTransaction = BudgetEvaluation.evaluate(
                            usdBudget("1.00"), List.of(oneWon, oneWon, oneWon), three, null)
                    .total();
            BudgetLine single = BudgetEvaluation.evaluate(
                            usdBudget("1.00"), List.of(tx(CurrencyCode.KRW, "3", "3", SEP_10)), three, null)
                    .total();

            assertThat(perTransaction.status()).isEqualTo(BudgetStatus.NEAR_LIMIT);
            assertThat(perTransaction.actualAmount()).isEqualByComparingTo("0.99");
            assertThat(single.status()).isEqualTo(BudgetStatus.REACHED);
        }

        @Test
        @DisplayName("나눗셈은 HALF_UP 이다 — 2원÷3 세 번은 0.6666666667×3 = 2.0000000001 로 예산 2 를 넘는다")
        void rounds_half_up_per_transaction() {
            TtsTimeline three =
                    TtsTimeline.of(List.of(ExchangeRate.of(CurrencyCode.USD, amount("3"), LocalDate.of(2026, 9, 1))));
            BudgetTransaction twoWon = tx(CurrencyCode.KRW, "2", "2", SEP_10);

            BudgetLine total = BudgetEvaluation.evaluate(
                            usdBudget("2.00"), List.of(twoWon, twoWon, twoWon), three, null)
                    .total();

            // DOWN 이면 0.6666666666×3 = 1.9999999998 → NEAR_LIMIT 로 갈린다
            assertThat(total.status()).isEqualTo(BudgetStatus.EXCEEDED);
            assertThat(total.overAmount()).isEqualByComparingTo("0.01");
        }

        @Test
        @DisplayName("나눗셈은 scale 10 이다 — 1원÷11 열한 번은 0.0909090909×11 = 0.9999999999 로 예산 1 에 못 미친다")
        void divides_at_scale_ten() {
            TtsTimeline eleven =
                    TtsTimeline.of(List.of(ExchangeRate.of(CurrencyCode.USD, amount("11"), LocalDate.of(2026, 9, 1))));
            BudgetTransaction oneWon = tx(CurrencyCode.KRW, "1", "1", SEP_10);

            BudgetLine total = BudgetEvaluation.evaluate(
                            usdBudget("1.00"), Collections.nCopies(11, oneWon), eleven, null)
                    .total();

            // scale 9 면 0.090909091×11 = 1.000000001 → EXCEEDED 로 갈린다
            assertThat(total.status()).isEqualTo(BudgetStatus.NEAR_LIMIT);
            assertThat(total.actualAmount()).isEqualByComparingTo("0.99");
        }

        @Test
        @DisplayName("정확히 반은 올린다 — 1원÷2048 = 0.00048828125 는 0.0004882813, 2048번 합 1.0000001024 로 예산 1 을 넘는다")
        void rounds_exact_half_up() {
            TtsTimeline rate = TtsTimeline.of(
                    List.of(ExchangeRate.of(CurrencyCode.USD, amount("2048"), LocalDate.of(2026, 9, 1))));
            BudgetTransaction oneWon = tx(CurrencyCode.KRW, "1", "1", SEP_10);

            BudgetLine total = BudgetEvaluation.evaluate(
                            usdBudget("1.00"), Collections.nCopies(2048, oneWon), rate, null)
                    .total();

            // HALF_EVEN 이면 0.0004882812×2048 = 0.9999998976 → NEAR_LIMIT 로 갈린다
            assertThat(total.status()).isEqualTo(BudgetStatus.EXCEEDED);
            assertThat(total.overAmount()).isEqualByComparingTo("0.01");
        }

        @Test
        @DisplayName("tts 는 거래일 이하 가장 최근 값이다 — 미래 거래는 마지막 값으로 떨어진다")
        void uses_latest_rate_on_or_before_transaction_date() {
            TtsTimeline timeline = TtsTimeline.of(List.of(
                    ExchangeRate.of(CurrencyCode.USD, amount("1000"), LocalDate.of(2026, 9, 1)),
                    ExchangeRate.of(CurrencyCode.USD, amount("2000"), LocalDate.of(2026, 9, 15))));

            BudgetEvaluation evaluation = BudgetEvaluation.evaluate(
                    usdBudget("100.00"),
                    List.of(
                            tx(CurrencyCode.KRW, "1000", "1000", LocalDate.of(2026, 9, 14)),
                            tx(CurrencyCode.KRW, "2000", "2000", LocalDate.of(2026, 9, 30))),
                    timeline,
                    null);

            assertThat(evaluation.total().actualAmount()).isEqualByComparingTo("2.00");
        }
    }

    @Nested
    @DisplayName("몫·그 외 카테고리·하루 권장액")
    class Allocation {

        @Test
        @DisplayName("결제수단 그룹 3줄을 항상 준다 — 몫이 없는 그룹은 실제 금액만, 그룹 합 = 전체")
        void expense_always_has_three_payment_groups() {
            BudgetAmounts budget = new BudgetAmounts(
                    CurrencyCode.KRW, amount("1000"), Map.of(PaymentGroup.CREDIT_CARD, amount("500")), Map.of());
            List<BudgetTransaction> transactions = List.of(
                    atAsset(1L, "100"), atAsset(2L, "20"), atAsset(3L, "30"), atAsset(4L, "4"), atAsset(99L, "5"));

            BudgetEvaluation evaluation = expense(budget, transactions);

            assertThat(evaluation.paymentGroups()).containsOnlyKeys(PaymentGroup.values());
            BudgetLine card = evaluation.paymentGroups().get(PaymentGroup.CREDIT_CARD);
            assertThat(card.budgetAmount()).isEqualByComparingTo("500");
            assertThat(card.actualAmount()).isEqualByComparingTo("100");
            assertThat(card.status()).isEqualTo(BudgetStatus.IN_PROGRESS);
            assertThat(card.percent()).isEqualTo(20);

            BudgetLine cash = evaluation.paymentGroups().get(PaymentGroup.CASH_AND_DEBIT);
            assertThat(cash.actualAmount()).isEqualByComparingTo("50");
            assertThat(cash.budgetAmount()).isNull();
            assertThat(cash.status()).isNull();
            assertThat(cash.percent()).isNull();
            assertThat(cash.remainingAmount()).isNull();
            assertThat(cash.overAmount()).isNull();
            assertThat(evaluation
                            .paymentGroups()
                            .get(PaymentGroup.ACCOUNT_AND_OTHER)
                            .actualAmount())
                    .isEqualByComparingTo("9");
            assertThat(evaluation.total().actualAmount()).isEqualByComparingTo("159");
        }

        @Test
        @DisplayName("카테고리 줄은 몫이 있는 것만, 나머지 카테고리는 otherCategories 줄의 actualAmount 로 합친다")
        void categories_only_with_allocation() {
            BudgetAmounts budget = new BudgetAmounts(
                    CurrencyCode.KRW, amount("1000"), Map.of(), Map.of(FOOD, amount("300"), 30L, amount("100")));
            List<BudgetTransaction> transactions = List.of(
                    inCategory(FOOD, "250"), inCategory(CAFE, "70"), inCategory(CAFE, "30"), inCategory(40L, "5"));

            BudgetEvaluation evaluation = expense(budget, transactions);

            assertThat(evaluation.categories()).containsOnlyKeys(FOOD, 30L);
            assertThat(evaluation.categories().get(FOOD).actualAmount()).isEqualByComparingTo("250");
            assertThat(evaluation.categories().get(FOOD).status()).isEqualTo(BudgetStatus.NEAR_LIMIT);
            assertThat(evaluation.categories().get(30L).status()).isEqualTo(BudgetStatus.NONE);
            assertThat(evaluation.otherCategories().actualAmount()).isEqualByComparingTo("105");
        }

        @Test
        @DisplayName("그 외 카테고리 줄 — 몫 = 전체 − 카테고리 몫 합(1000 − 600 = 400), 몫 없는 카테고리에 350 이면 NEAR_LIMIT·87%·남은 50")
        void other_categories_line_uses_remaining_share() {
            BudgetAmounts budget = new BudgetAmounts(
                    CurrencyCode.KRW,
                    amount("1000"),
                    Map.of(PaymentGroup.CREDIT_CARD, amount("900")),
                    Map.of(FOOD, amount("600")));

            BudgetLine other = expense(budget, List.of(inCategory(FOOD, "100"), inCategory(CAFE, "350")))
                    .otherCategories();

            assertThat(other.budgetAmount()).isEqualByComparingTo("400");
            assertThat(other.actualAmount()).isEqualByComparingTo("350");
            assertThat(other.status()).isEqualTo(BudgetStatus.NEAR_LIMIT);
            assertThat(other.percent()).isEqualTo(87);
            assertThat(other.remainingAmount()).isEqualByComparingTo("50");
            assertThat(other.overAmount()).isNull();
        }

        @Test
        @DisplayName("그 외 카테고리 줄도 다른 줄처럼 넘는다 — 몫 400 에 450 이면 EXCEEDED·넘은 50·남은 null")
        void other_categories_line_exceeds_like_other_lines() {
            BudgetAmounts budget =
                    new BudgetAmounts(CurrencyCode.KRW, amount("1000"), Map.of(), Map.of(FOOD, amount("600")));

            BudgetLine other = expense(budget, List.of(inCategory(CAFE, "450"))).otherCategories();

            assertThat(other.budgetAmount()).isEqualByComparingTo("400");
            assertThat(other.actualAmount()).isEqualByComparingTo("450");
            assertThat(other.status()).isEqualTo(BudgetStatus.EXCEEDED);
            assertThat(other.percent()).isNull();
            assertThat(other.remainingAmount()).isNull();
            assertThat(other.overAmount()).isEqualByComparingTo("50");
        }

        @Test
        @DisplayName("카테고리 몫 합 = 전체면 남는 몫이 없어 그 외 카테고리 줄은 쓴 돈만 있다")
        void other_categories_line_is_actual_only_when_no_share_remains() {
            BudgetAmounts budget = new BudgetAmounts(
                    CurrencyCode.KRW, amount("1000"), Map.of(), Map.of(FOOD, amount("400"), CAFE, amount("600")));

            BudgetLine other = expense(budget, List.of(inCategory(40L, "30"))).otherCategories();

            assertThat(other.actualAmount()).isEqualByComparingTo("30");
            assertThat(other.budgetAmount()).isNull();
            assertThat(other.status()).isNull();
            assertThat(other.percent()).isNull();
            assertThat(other.remainingAmount()).isNull();
            assertThat(other.overAmount()).isNull();
        }

        @Test
        @DisplayName("카테고리 몫이 없으면 그 외 카테고리 줄 = 전체 줄(몫 = 전체, 쓴 돈 = 전체 실제)")
        void other_categories_line_equals_total_without_category_shares() {
            BudgetEvaluation evaluation =
                    expense(krwBudget("1000"), List.of(inCategory(FOOD, "300"), inCategory(CAFE, "200")));

            BudgetLine other = evaluation.otherCategories();
            assertThat(other.budgetAmount()).isEqualByComparingTo("1000");
            assertThat(other.actualAmount()).isEqualByComparingTo("500");
            assertThat(other.status()).isEqualTo(BudgetStatus.IN_PROGRESS);
            assertThat(other.percent()).isEqualTo(50);
            assertThat(other).isEqualTo(evaluation.total());
        }

        @Test
        @DisplayName("외화 예산에서 환율이 없는 몫 없는 카테고리 거래는 그 외 카테고리 쓴 돈에서 빠지고 missingRateCount 로만 센다")
        void other_categories_line_excludes_missing_rate_transactions() {
            TtsTimeline usdFromSep1 = TtsTimeline.of(
                    List.of(ExchangeRate.of(CurrencyCode.USD, amount("1350"), LocalDate.of(2026, 9, 1))));
            BudgetAmounts budget =
                    new BudgetAmounts(CurrencyCode.USD, amount("100.00"), Map.of(), Map.of(FOOD, amount("40.00")));
            BudgetTransaction beforeAnyRate = new BudgetTransaction(
                    CurrencyCode.KRW, amount("13500"), amount("13500"), LocalDate.of(2026, 8, 31), CAFE, CARD);
            BudgetTransaction withRate = inCategory(CAFE, "2700");

            BudgetEvaluation evaluation =
                    BudgetEvaluation.evaluate(budget, List.of(beforeAnyRate, withRate), usdFromSep1, null);

            assertThat(evaluation.missingRateCount()).isEqualTo(1);
            assertThat(evaluation.otherCategories().budgetAmount()).isEqualByComparingTo("60.00");
            assertThat(evaluation.otherCategories().actualAmount()).isEqualByComparingTo("2.00");
            assertThat(evaluation.otherCategories().percent()).isEqualTo(3);
        }

        @Test
        @DisplayName("카테고리 몫 합이 전체를 넘는 v1 행(남는 몫 < 0)이면 예외 없이 쓴 돈만 있는 줄이다")
        void other_categories_line_is_actual_only_when_shares_exceed_total() {
            BudgetAmounts budget = new BudgetAmounts(
                    CurrencyCode.KRW, amount("1000"), Map.of(), Map.of(FOOD, amount("700"), CAFE, amount("500")));

            BudgetLine other = expense(budget, List.of(inCategory(40L, "5"))).otherCategories();

            assertThat(other.actualAmount()).isEqualByComparingTo("5");
            assertThat(other.budgetAmount()).isNull();
            assertThat(other.status()).isNull();
            assertThat(other.percent()).isNull();
            assertThat(other.remainingAmount()).isNull();
            assertThat(other.overAmount()).isNull();
        }

        @Test
        @DisplayName("하루 권장액 = (전체 − 실제) ÷ 오늘 포함 남은 일수, 통화 자릿수에서 내림 — 말일(1일 남음)도 계산한다")
        void daily_allowance() {
            BudgetEvaluation threeDaysLeft =
                    BudgetEvaluation.evaluate(krwBudget("1000"), List.of(krw("0.50")), null, 3);
            // (1000 − 0.5) ÷ 3 = 333.1666… → 333
            assertThat(threeDaysLeft.dailyAllowance().amount()).isEqualByComparingTo("333");
            assertThat(threeDaysLeft.dailyAllowance().exceeded()).isFalse();

            BudgetEvaluation lastDay = BudgetEvaluation.evaluate(krwBudget("1000"), List.of(krw("400")), null, 1);
            assertThat(lastDay.dailyAllowance().amount()).isEqualByComparingTo("600");
        }

        @Test
        @DisplayName("이번 달이 아니면(남은 일수 null) 하루 권장액이 없다")
        void no_daily_allowance_outside_current_month() {
            assertThat(expense(krwBudget("1000"), List.of()).dailyAllowance()).isNull();
        }

        private BudgetTransaction atAsset(long assetId, String krwAmount) {
            return new BudgetTransaction(CurrencyCode.KRW, amount(krwAmount), amount(krwAmount), SEP_10, FOOD, assetId);
        }

        private BudgetTransaction inCategory(long categoryId, String krwAmount) {
            return new BudgetTransaction(
                    CurrencyCode.KRW, amount(krwAmount), amount(krwAmount), SEP_10, categoryId, CARD);
        }
    }
}

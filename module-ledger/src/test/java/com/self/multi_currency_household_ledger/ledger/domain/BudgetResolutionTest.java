package com.self.multi_currency_household_ledger.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.self.multi_currency_household_ledger.exchange.domain.CurrencyCode;
import com.self.multi_currency_household_ledger.ledger.domain.Budget.CategoryAmount;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BudgetResolutionTest {

    private static final UUID MEMBER = UUID.randomUUID();
    private static final YearMonth JULY = YearMonth.of(2026, 7);
    private static final YearMonth AUGUST = YearMonth.of(2026, 8);
    private static final YearMonth SEPTEMBER = YearMonth.of(2026, 9);
    private static final YearMonth OCTOBER = YearMonth.of(2026, 10);

    private static Budget valued(BudgetKind kind, YearMonth month, String total) {
        Budget budget = new Budget(MEMBER, TransactionType.EXPENSE, kind, month, null, null, List.of());
        budget.replaceAmounts(
                CurrencyCode.KRW, new BigDecimal(total), List.of(), List.of(new CategoryAmount(3L, BigDecimal.TEN)));
        return budget;
    }

    private static Budget off(BudgetKind kind, YearMonth month) {
        return new Budget(MEMBER, TransactionType.EXPENSE, kind, month, null, null, List.of());
    }

    @Test
    @DisplayName("① 달별 값이 없고 적용 기본값이 금액 세트면 DEFAULT")
    void default_value() {
        BudgetResolution resolution =
                BudgetResolution.resolve(List.of(valued(BudgetKind.DEFAULT, AUGUST, "1000")), SEPTEMBER);

        assertThat(resolution.source()).isEqualTo(BudgetSource.DEFAULT);
        assertThat(resolution.amounts().total()).isEqualByComparingTo("1000");
        assertThat(resolution.amounts().categoryAmounts()).containsOnlyKeys(3L);
    }

    @Test
    @DisplayName("② 달별 값이 없고 적용 기본값이 끔이면 DEFAULT_OFF")
    void default_off() {
        BudgetResolution resolution = BudgetResolution.resolve(
                List.of(valued(BudgetKind.DEFAULT, JULY, "1000"), off(BudgetKind.DEFAULT, AUGUST)), SEPTEMBER);

        assertThat(resolution.source()).isEqualTo(BudgetSource.DEFAULT_OFF);
        assertThat(resolution.amounts()).isNull();
    }

    @Test
    @DisplayName("③ 그 달의 달별 값이 있으면 기본값보다 앞서 MONTH_VALUE")
    void month_value() {
        BudgetResolution resolution = BudgetResolution.resolve(
                List.of(valued(BudgetKind.DEFAULT, SEPTEMBER, "1000"), valued(BudgetKind.MONTH, SEPTEMBER, "500")),
                SEPTEMBER);

        assertThat(resolution.source()).isEqualTo(BudgetSource.MONTH_VALUE);
        assertThat(resolution.amounts().total()).isEqualByComparingTo("500");
    }

    @Test
    @DisplayName("④ 그 달의 달별 끔이 있으면 기본값이 있어도 MONTH_OFF")
    void month_off() {
        BudgetResolution resolution = BudgetResolution.resolve(
                List.of(valued(BudgetKind.DEFAULT, AUGUST, "1000"), off(BudgetKind.MONTH, SEPTEMBER)), SEPTEMBER);

        assertThat(resolution.source()).isEqualTo(BudgetSource.MONTH_OFF);
        assertThat(resolution.amounts()).isNull();
    }

    @Test
    @DisplayName("⑤ 행이 없으면 NOT_SET")
    void not_set() {
        BudgetResolution resolution = BudgetResolution.resolve(List.of(), SEPTEMBER);

        assertThat(resolution.source()).isEqualTo(BudgetSource.NOT_SET);
        assertThat(resolution.amounts()).isNull();
    }

    @Test
    @DisplayName("적용 시작 달이 M 보다 늦은 기본값과 다른 달의 달별 값은 무시한다 — 그것뿐이면 NOT_SET")
    void ignores_later_default_and_other_month_rows() {
        BudgetResolution resolution = BudgetResolution.resolve(
                List.of(
                        valued(BudgetKind.DEFAULT, OCTOBER, "9000"),
                        valued(BudgetKind.MONTH, AUGUST, "7000"),
                        off(BudgetKind.MONTH, OCTOBER)),
                SEPTEMBER);

        assertThat(resolution.source()).isEqualTo(BudgetSource.NOT_SET);
        assertThat(resolution.amounts()).isNull();
    }

    @Test
    @DisplayName("적용 시작 달이 바로 M 인 기본값만 있어도 DEFAULT 로 적용된다")
    void default_starting_in_month_applies() {
        BudgetResolution resolution =
                BudgetResolution.resolve(List.of(valued(BudgetKind.DEFAULT, SEPTEMBER, "1000")), SEPTEMBER);

        assertThat(resolution.source()).isEqualTo(BudgetSource.DEFAULT);
        assertThat(resolution.amounts().total()).isEqualByComparingTo("1000");
    }

    @Test
    @DisplayName("M 이하 기본값 중 가장 늦은 것을 쓴다 — 늦은 DEFAULT(10월)는 9월 해석에 끼지 않는다")
    void picks_latest_default_on_or_before_month() {
        BudgetResolution resolution = BudgetResolution.resolve(
                List.of(
                        valued(BudgetKind.DEFAULT, AUGUST, "2000"),
                        valued(BudgetKind.DEFAULT, OCTOBER, "9000"),
                        valued(BudgetKind.DEFAULT, JULY, "1000")),
                SEPTEMBER);

        assertThat(resolution.source()).isEqualTo(BudgetSource.DEFAULT);
        assertThat(resolution.amounts().total()).isEqualByComparingTo("2000");
    }
}

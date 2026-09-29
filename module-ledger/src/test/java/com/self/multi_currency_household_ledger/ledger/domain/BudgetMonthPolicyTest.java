package com.self.multi_currency_household_ledger.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.self.multi_currency_household_ledger.common.exception.BusinessException;
import com.self.multi_currency_household_ledger.ledger.exception.BudgetErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BudgetMonthPolicyTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    // 2026-09-30 23:59 KST (UTC 14:59) — 9월의 마지막 1분
    private static final BudgetMonthPolicy SEPTEMBER_END = policyAt("2026-09-30T14:59:00Z");
    // 2026-10-01 00:00 KST (UTC 15:00) — 10월의 첫 순간
    private static final BudgetMonthPolicy OCTOBER_START = policyAt("2026-09-30T15:00:00Z");

    private static BudgetMonthPolicy policyAt(String instant) {
        return new BudgetMonthPolicy(Clock.fixed(Instant.parse(instant), KST));
    }

    @Test
    @DisplayName("이번 달은 서버 Clock 의 KST 기준이다 — 월말 23:59 와 월초 00:00 이 갈린다")
    void current_follows_kst_boundary() {
        assertThat(SEPTEMBER_END.current()).isEqualTo(YearMonth.of(2026, 9));
        assertThat(OCTOBER_START.current()).isEqualTo(YearMonth.of(2026, 10));
    }

    @Test
    @DisplayName("이번 달과 다음 달만 쓸 수 있다")
    void editable_only_current_and_next() {
        assertThat(SEPTEMBER_END.isEditable(YearMonth.of(2026, 8))).isFalse();
        assertThat(SEPTEMBER_END.isEditable(YearMonth.of(2026, 9))).isTrue();
        assertThat(SEPTEMBER_END.isEditable(YearMonth.of(2026, 10))).isTrue();
        assertThat(SEPTEMBER_END.isEditable(YearMonth.of(2026, 11))).isFalse();

        assertThat(OCTOBER_START.isEditable(YearMonth.of(2026, 9))).isFalse();
        assertThat(OCTOBER_START.isEditable(YearMonth.of(2026, 11))).isTrue();
    }

    @Test
    @DisplayName("이번 달·다음 달 쓰기는 통과한다")
    void require_writable_allows_current_and_next() {
        assertThatCode(() -> SEPTEMBER_END.requireWritable(YearMonth.of(2026, 9)))
                .doesNotThrowAnyException();
        assertThatCode(() -> SEPTEMBER_END.requireWritable(YearMonth.of(2026, 10)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("지난 달 쓰기는 BUDGET_PAST_MONTH 로 거절한다 — 월초 00:00 이면 방금 끝난 달도 지난 달이다")
    void require_writable_rejects_past_month() {
        assertCode(() -> SEPTEMBER_END.requireWritable(YearMonth.of(2026, 8)), BudgetErrorCode.BUDGET_PAST_MONTH);
        assertCode(() -> OCTOBER_START.requireWritable(YearMonth.of(2026, 9)), BudgetErrorCode.BUDGET_PAST_MONTH);
    }

    @Test
    @DisplayName("다음 달보다 늦은 달 쓰기는 BUDGET_MONTH_TOO_FAR 로 거절한다")
    void require_writable_rejects_too_far_month() {
        assertCode(() -> SEPTEMBER_END.requireWritable(YearMonth.of(2026, 11)), BudgetErrorCode.BUDGET_MONTH_TOO_FAR);
        assertCode(() -> OCTOBER_START.requireWritable(YearMonth.of(2026, 12)), BudgetErrorCode.BUDGET_MONTH_TOO_FAR);
    }

    @Test
    @DisplayName("오늘을 포함한 남은 일수는 이번 달일 때만 있다")
    void remaining_days_only_for_current_month() {
        assertThat(SEPTEMBER_END.remainingDaysIncludingToday(YearMonth.of(2026, 9)))
                .isEqualTo(1);
        assertThat(OCTOBER_START.remainingDaysIncludingToday(YearMonth.of(2026, 10)))
                .isEqualTo(31);
        assertThat(SEPTEMBER_END.remainingDaysIncludingToday(YearMonth.of(2026, 10)))
                .isNull();
        assertThat(OCTOBER_START.remainingDaysIncludingToday(YearMonth.of(2026, 9)))
                .isNull();
    }

    private static void assertCode(Runnable call, BudgetErrorCode expected) {
        assertThatThrownBy(call::run)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(expected.getCode());
    }
}

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
import java.util.List;
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
    @DisplayName("2000-01·지난 달·이번 달·이번 달 + 12 쓰기는 통과한다")
    void require_writable_accepts_2000_01_through_twelve_months_ahead() {
        for (YearMonth month :
                List.of(YearMonth.of(2000, 1), YearMonth.of(2026, 8), YearMonth.of(2026, 9), YearMonth.of(2027, 9))) {
            assertThatCode(() -> SEPTEMBER_END.requireWritable(month)).doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("2000-01 앞의 달 쓰기는 BUDGET_MONTH_OUT_OF_RANGE 로 거절한다")
    void require_writable_rejects_before_2000_01() {
        assertCode(
                () -> SEPTEMBER_END.requireWritable(YearMonth.of(1999, 12)), BudgetErrorCode.BUDGET_MONTH_OUT_OF_RANGE);
    }

    @Test
    @DisplayName("이번 달 + 13 쓰기는 BUDGET_MONTH_OUT_OF_RANGE 로 거절한다")
    void require_writable_rejects_thirteen_months_ahead() {
        assertCode(
                () -> SEPTEMBER_END.requireWritable(YearMonth.of(2027, 10)), BudgetErrorCode.BUDGET_MONTH_OUT_OF_RANGE);
    }

    @Test
    @DisplayName("쓰기 상한은 KST 월 경계를 따른다 — 12-31 23:59:59 면 2027-12 까지, 01-01 00:00 이면 2028-01 까지")
    void writable_range_follows_seoul_month_boundary() {
        BudgetMonthPolicy decemberEnd = policyAt("2026-12-31T14:59:59Z");
        BudgetMonthPolicy januaryStart = policyAt("2026-12-31T15:00:00Z");

        assertThatCode(() -> decemberEnd.requireWritable(YearMonth.of(2027, 12)))
                .doesNotThrowAnyException();
        assertCode(() -> decemberEnd.requireWritable(YearMonth.of(2028, 1)), BudgetErrorCode.BUDGET_MONTH_OUT_OF_RANGE);
        assertThatCode(() -> januaryStart.requireWritable(YearMonth.of(2028, 1)))
                .doesNotThrowAnyException();
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

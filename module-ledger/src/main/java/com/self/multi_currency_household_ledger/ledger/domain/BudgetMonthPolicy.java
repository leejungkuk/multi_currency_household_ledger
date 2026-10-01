package com.self.multi_currency_household_ledger.ledger.domain;

import com.self.multi_currency_household_ledger.common.exception.BusinessException;
import com.self.multi_currency_household_ledger.ledger.exception.BudgetErrorCode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;

/** 예산을 쓸 수 있는 달은 2000-01 부터 서버 Clock(Asia/Seoul) 기준 이번 달 + 12 까지다(지난 달 포함). */
public final class BudgetMonthPolicy {

    private static final YearMonth EARLIEST_WRITABLE = YearMonth.of(2000, 1);

    private final Clock clock;

    public BudgetMonthPolicy(Clock clock) {
        this.clock = clock;
    }

    public YearMonth current() {
        return YearMonth.now(clock);
    }

    // +12 는 거래 미래일 상한(오늘 KST + 365일)이 닿는 가장 늦은 달이다.
    public void requireWritable(YearMonth month) {
        if (month.isBefore(EARLIEST_WRITABLE) || month.isAfter(current().plusMonths(12))) {
            throw new BusinessException(BudgetErrorCode.BUDGET_MONTH_OUT_OF_RANGE);
        }
    }

    /** 이번 달이면 오늘을 포함한 남은 일수, 아니면 null. */
    public Integer remainingDaysIncludingToday(YearMonth month) {
        LocalDate today = LocalDate.now(clock);
        if (!YearMonth.from(today).equals(month)) {
            return null;
        }
        return month.lengthOfMonth() - today.getDayOfMonth() + 1;
    }
}

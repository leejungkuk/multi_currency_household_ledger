package com.self.multi_currency_household_ledger.ledger.domain;

import com.self.multi_currency_household_ledger.common.exception.BusinessException;
import com.self.multi_currency_household_ledger.ledger.exception.BudgetErrorCode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;

/** 예산을 쓸 수 있는 달은 서버 Clock(Asia/Seoul) 기준 이번 달과 다음 달뿐이다. 읽기는 지난 달도 된다. */
public final class BudgetMonthPolicy {

    private final Clock clock;

    public BudgetMonthPolicy(Clock clock) {
        this.clock = clock;
    }

    public YearMonth current() {
        return YearMonth.now(clock);
    }

    public boolean isEditable(YearMonth month) {
        YearMonth current = current();
        return !month.isBefore(current) && !month.isAfter(current.plusMonths(1));
    }

    public void requireWritable(YearMonth month) {
        YearMonth current = current();
        if (month.isBefore(current)) {
            throw new BusinessException(BudgetErrorCode.BUDGET_PAST_MONTH);
        }
        if (month.isAfter(current.plusMonths(1))) {
            throw new BusinessException(BudgetErrorCode.BUDGET_MONTH_TOO_FAR);
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

package com.self.multi_currency_household_ledger.exchange.service;

import com.self.multi_currency_household_ledger.common.exception.BusinessException;
import com.self.multi_currency_household_ledger.exchange.domain.CurrencyCode;
import com.self.multi_currency_household_ledger.exchange.domain.ExchangeRateRepository;
import com.self.multi_currency_household_ledger.exchange.exception.ExchangeErrorCode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExchangeRateBackfillService {

    private static final int MAX_CONSECUTIVE_FAILURES = 3;

    private final ExchangeRateRepository exchangeRateRepository;
    private final ExchangeRateService exchangeRateService;

    public BackfillResult backfill(LocalDate from, LocalDate to) {
        List<CurrencyCode> expectedCodes = Arrays.stream(CurrencyCode.values())
                .filter(code -> !code.isBase())
                .toList();
        Set<LocalDate> completeDates = new HashSet<>(
                exchangeRateRepository.findCompleteBaseDates(from, to, expectedCodes, expectedCodes.size()));
        List<LocalDate> missingDates = from.datesUntil(to.plusDays(1))
                .filter(ExchangeRateBackfillService::isWeekday)
                .filter(date -> !completeDates.contains(date))
                .toList();
        List<LocalDate> orderedDates = prioritizeEndDate(missingDates, to);

        BackfillStatus status = BackfillStatus.COMPLETED;
        int filledDays = 0;
        int failedDays = 0;
        int consecutiveFailures = 0;

        for (LocalDate date : orderedDates) {
            try {
                int saved = exchangeRateService.fetchAndSaveRates(date);
                if (saved > 0) {
                    filledDays++;
                }
                consecutiveFailures = 0;
            } catch (BusinessException e) {
                if (ExchangeErrorCode.EXCHANGE_API_AUTH_ERROR.getCode().equals(e.getCode())) {
                    log.error("환율 백필 인증 오류로 중단합니다. date={}", date, e);
                    status = BackfillStatus.AUTH_ABORTED;
                    break;
                }
                if (ExchangeErrorCode.EXCHANGE_API_LIMIT_EXCEEDED.getCode().equals(e.getCode())) {
                    log.warn("환율 백필 쿼터 소진으로 중단합니다. date={}", date, e);
                    status = BackfillStatus.QUOTA_ABORTED;
                    break;
                }
                log.warn("환율 백필 날짜 처리에 실패했습니다. date={}", date, e);
                failedDays++;
                consecutiveFailures++;
                if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                    log.warn("환율 백필 연속 실패로 중단합니다. date={}, consecutive={}", date, consecutiveFailures);
                    status = BackfillStatus.TRANSIENT_ABORTED;
                    break;
                }
            } catch (RuntimeException e) {
                log.warn("환율 백필 날짜 처리에 실패했습니다. date={}", date, e);
                failedDays++;
                consecutiveFailures++;
                if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                    log.warn("환율 백필 연속 실패로 중단합니다. date={}, consecutive={}", date, consecutiveFailures);
                    status = BackfillStatus.TRANSIENT_ABORTED;
                    break;
                }
            }
        }

        boolean endDateComplete = !exchangeRateRepository
                .findCompleteBaseDates(to, to, expectedCodes, expectedCodes.size())
                .isEmpty();
        return new BackfillResult(status, filledDays, failedDays, endDateComplete);
    }

    private static boolean isWeekday(LocalDate date) {
        return date.getDayOfWeek() != DayOfWeek.SATURDAY && date.getDayOfWeek() != DayOfWeek.SUNDAY;
    }

    private static List<LocalDate> prioritizeEndDate(List<LocalDate> missingDates, LocalDate endDate) {
        List<LocalDate> ordered = new ArrayList<>(missingDates.size());
        if (missingDates.contains(endDate)) {
            ordered.add(endDate);
        }
        missingDates.stream().filter(date -> !date.equals(endDate)).forEach(ordered::add);
        return ordered;
    }

    public enum BackfillStatus {
        COMPLETED,
        AUTH_ABORTED,
        QUOTA_ABORTED,
        TRANSIENT_ABORTED
    }

    public record BackfillResult(BackfillStatus status, int filledDays, int failedDays, boolean endDateComplete) {}
}

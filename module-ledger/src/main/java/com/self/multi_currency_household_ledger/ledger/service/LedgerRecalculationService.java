package com.self.multi_currency_household_ledger.ledger.service;

import com.self.multi_currency_household_ledger.exchange.domain.ExchangeRate;
import com.self.multi_currency_household_ledger.exchange.service.ExchangeRateService;
import com.self.multi_currency_household_ledger.ledger.domain.LedgerEntry;
import com.self.multi_currency_household_ledger.ledger.domain.LedgerEntryRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LedgerRecalculationService {

    private final LedgerEntryRepository ledgerEntryRepository;
    private final ExchangeRateService exchangeRateService;
    private final Clock clock;
    private final int correctionWindowDays;

    public LedgerRecalculationService(
            LedgerEntryRepository ledgerEntryRepository,
            ExchangeRateService exchangeRateService,
            Clock clock,
            @Value("${ledger.recalculation.window-days:7}") int correctionWindowDays) {
        validateCorrectionWindow(correctionWindowDays);
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.exchangeRateService = exchangeRateService;
        this.clock = clock;
        this.correctionWindowDays = correctionWindowDays;
    }

    @Transactional
    public int recalculateRecentForeignEntries() {
        LocalDate today = LocalDate.now(clock);
        LocalDate startDate = today.minusDays(correctionWindowDays);
        List<LedgerEntry> candidates = ledgerEntryRepository.findForeignEntriesOnOrAfter(startDate);

        int recalculated = 0;
        for (LedgerEntry entry : candidates) {
            if (entry.getCurrencyCode().isBase()) {
                continue;
            }

            ExchangeRate applicableRate =
                    exchangeRateService.getRateOnOrBefore(entry.getCurrencyCode(), entry.getTransactionDate());
            if (entry.recalculate(applicableRate.getTts(), applicableRate.getBaseDate())) {
                recalculated++;
            }
        }
        return recalculated;
    }

    private void validateCorrectionWindow(int correctionWindowDays) {
        if (correctionWindowDays < 3 || correctionWindowDays > 7) {
            throw new IllegalArgumentException("correctionWindowDays must be between 3 and 7");
        }
    }
}

package com.self.multi_currency_household_ledger.ledger.service;

import com.self.multi_currency_household_ledger.common.exception.BusinessException;
import com.self.multi_currency_household_ledger.exchange.domain.ExchangeRate;
import com.self.multi_currency_household_ledger.exchange.service.ExchangeRateService;
import com.self.multi_currency_household_ledger.ledger.domain.Asset;
import com.self.multi_currency_household_ledger.ledger.domain.AssetRepository;
import com.self.multi_currency_household_ledger.ledger.domain.Category;
import com.self.multi_currency_household_ledger.ledger.domain.CategoryRepository;
import com.self.multi_currency_household_ledger.ledger.domain.LedgerEntry;
import com.self.multi_currency_household_ledger.ledger.domain.LedgerEntryRepository;
import com.self.multi_currency_household_ledger.ledger.domain.TransactionType;
import com.self.multi_currency_household_ledger.ledger.dto.CreateLedgerEntryRequest;
import com.self.multi_currency_household_ledger.ledger.dto.LedgerEntryResponse;
import com.self.multi_currency_household_ledger.ledger.dto.LedgerMonthlySummaryResponse;
import com.self.multi_currency_household_ledger.ledger.dto.LedgerReportResponse;
import com.self.multi_currency_household_ledger.ledger.exception.LedgerErrorCode;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class LedgerService {

    private static final int MONTHLY_ENTRY_LIMIT = 500;

    private final LedgerEntryRepository ledgerEntryRepository;
    private final CategoryRepository categoryRepository;
    private final AssetRepository assetRepository;
    private final ExchangeRateService exchangeRateService;
    private final Clock clock;

    @Transactional
    public LedgerEntryResponse create(CreateLedgerEntryRequest request, UUID memberId) {
        Category category = categoryRepository
                .findById(request.categoryId())
                .orElseThrow(() -> new BusinessException(LedgerErrorCode.CATEGORY_NOT_FOUND));

        Asset asset = assetRepository
                .findById(request.assetId())
                .orElseThrow(() -> new BusinessException(LedgerErrorCode.ASSET_NOT_FOUND));

        ExchangeRate exchangeRate = null;
        if (!request.currencyCode().isBase()) {
            exchangeRate = exchangeRateService.getRateOnOrBefore(request.currencyCode(), request.transactionDate());
        }

        LedgerEntry entry = LedgerEntry.of(
                memberId,
                category,
                asset,
                request.amount(),
                request.currencyCode(),
                request.transactionDate(),
                request.memo(),
                exchangeRate,
                clock);

        LedgerEntry saved = ledgerEntryRepository.save(entry);
        return LedgerEntryResponse.from(saved);
    }

    @Transactional
    public LedgerEntryResponse update(Long id, CreateLedgerEntryRequest request, UUID memberId) {
        LedgerEntry entry = ledgerEntryRepository
                .findByIdAndMemberId(id, memberId)
                .orElseThrow(() -> new BusinessException(LedgerErrorCode.LEDGER_ENTRY_NOT_FOUND));

        Category category = categoryRepository
                .findById(request.categoryId())
                .orElseThrow(() -> new BusinessException(LedgerErrorCode.CATEGORY_NOT_FOUND));

        Asset asset = assetRepository
                .findById(request.assetId())
                .orElseThrow(() -> new BusinessException(LedgerErrorCode.ASSET_NOT_FOUND));

        ExchangeRate exchangeRate = null;
        if (!request.currencyCode().isBase()) {
            exchangeRate = exchangeRateService.getRateOnOrBefore(request.currencyCode(), request.transactionDate());
        }

        entry.replace(
                category,
                asset,
                request.amount(),
                request.currencyCode(),
                request.transactionDate(),
                request.memo(),
                exchangeRate,
                clock);
        return LedgerEntryResponse.from(entry);
    }

    @Transactional
    public void delete(Long id, UUID memberId) {
        LedgerEntry entry = ledgerEntryRepository
                .findByIdAndMemberId(id, memberId)
                .orElseThrow(() -> new BusinessException(LedgerErrorCode.LEDGER_ENTRY_NOT_FOUND));

        ledgerEntryRepository.delete(entry);
    }

    @Transactional(readOnly = true)
    public LedgerMonthlySummaryResponse getMonthlySummary(UUID memberId, int year, int month) {
        DateRange dateRange = DateRange.of(year, month);
        BigDecimal income = ledgerEntryRepository.sumKrwAmountByMemberIdAndTransactionTypeAndTransactionDateRange(
                memberId, TransactionType.INCOME, dateRange.startDate(), dateRange.endDate());
        BigDecimal expense = ledgerEntryRepository.sumKrwAmountByMemberIdAndTransactionTypeAndTransactionDateRange(
                memberId, TransactionType.EXPENSE, dateRange.startDate(), dateRange.endDate());

        return new LedgerMonthlySummaryResponse(income, expense);
    }

    @Transactional(readOnly = true)
    public List<LedgerEntryResponse> getMonthlyEntries(UUID memberId, int year, int month) {
        DateRange dateRange = DateRange.of(year, month);
        return ledgerEntryRepository
                .findByMemberIdAndTransactionDateGreaterThanEqualAndTransactionDateLessThanOrderByTransactionDateDescIdDesc(
                        memberId, dateRange.startDate(), dateRange.endDate(), PageRequest.of(0, MONTHLY_ENTRY_LIMIT))
                .stream()
                .map(LedgerEntryResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public LedgerReportResponse getMonthlyReport(UUID memberId, int year, int month) {
        DateRange dateRange = DateRange.of(year, month);
        List<LedgerReportResponse.CurrencySubtotal> currencySubtotals = ledgerEntryRepository
                .findCurrencySubtotalsByMemberIdAndTransactionDateRange(
                        memberId, dateRange.startDate(), dateRange.endDate())
                .stream()
                .map(LedgerReportResponse.CurrencySubtotal::from)
                .toList();
        List<LedgerReportResponse.CategorySubtotal> categorySubtotals = ledgerEntryRepository
                .findCategorySubtotalsByMemberIdAndTransactionDateRange(
                        memberId, dateRange.startDate(), dateRange.endDate())
                .stream()
                .map(LedgerReportResponse.CategorySubtotal::from)
                .toList();

        return new LedgerReportResponse(currencySubtotals, categorySubtotals);
    }

    private record DateRange(LocalDate startDate, LocalDate endDate) {

        private static DateRange of(int year, int month) {
            YearMonth yearMonth = YearMonth.of(year, month);
            LocalDate startDate = yearMonth.atDay(1);
            return new DateRange(startDate, yearMonth.plusMonths(1).atDay(1));
        }
    }
}

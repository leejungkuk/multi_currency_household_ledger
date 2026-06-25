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
import com.self.multi_currency_household_ledger.ledger.dto.ImportLedgerEntriesRequest;
import com.self.multi_currency_household_ledger.ledger.dto.ImportLedgerEntriesResponse;
import com.self.multi_currency_household_ledger.ledger.dto.LedgerEntryResponse;
import com.self.multi_currency_household_ledger.ledger.dto.LedgerMonthlySummaryResponse;
import com.self.multi_currency_household_ledger.ledger.dto.LedgerReportResponse;
import com.self.multi_currency_household_ledger.ledger.exception.LedgerErrorCode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
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
    public ImportLedgerEntriesResponse importEntries(ImportLedgerEntriesRequest request, UUID memberId) {
        validateUniqueClientEntryIds(request.entries());

        List<ImportLedgerEntriesResponse.ImportedLedgerEntry> entries =
                new ArrayList<>(request.entries().size());
        for (ImportLedgerEntriesRequest.ImportLedgerEntryItem item : request.entries()) {
            entries.add(importEntry(memberId, item));
        }
        return new ImportLedgerEntriesResponse(entries);
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

    private ImportLedgerEntriesResponse.ImportedLedgerEntry importEntry(
            UUID memberId, ImportLedgerEntriesRequest.ImportLedgerEntryItem item) {
        String payloadHash = calculateClientPayloadHash(item);
        return ledgerEntryRepository
                .findByMemberIdAndClientEntryId(memberId, item.clientEntryId())
                .map(existing -> existingImportResponse(item.clientEntryId(), existing, payloadHash))
                .orElseGet(() -> createImportedEntry(memberId, item, payloadHash));
    }

    private ImportLedgerEntriesResponse.ImportedLedgerEntry createImportedEntry(
            UUID memberId, ImportLedgerEntriesRequest.ImportLedgerEntryItem item, String payloadHash) {
        Category category = categoryRepository
                .findById(item.categoryId())
                .orElseThrow(() -> new BusinessException(LedgerErrorCode.CATEGORY_NOT_FOUND));

        Asset asset = assetRepository
                .findById(item.assetId())
                .orElseThrow(() -> new BusinessException(LedgerErrorCode.ASSET_NOT_FOUND));

        ExchangeRate exchangeRate = null;
        if (!item.currencyCode().isBase()) {
            exchangeRate = exchangeRateService.getRateOnOrBeforeOrOldest(item.currencyCode(), item.transactionDate());
        }

        LedgerEntry entry = LedgerEntry.of(
                memberId,
                category,
                asset,
                item.amount(),
                item.currencyCode(),
                item.transactionDate(),
                item.memo(),
                exchangeRate,
                clock);
        entry.assignClientEntry(item.clientEntryId(), payloadHash);

        try {
            LedgerEntry saved = ledgerEntryRepository.saveAndFlush(entry);
            return new ImportLedgerEntriesResponse.ImportedLedgerEntry(
                    item.clientEntryId(), LedgerEntryResponse.from(saved));
        } catch (DataIntegrityViolationException e) {
            throw importConflict();
        }
    }

    private ImportLedgerEntriesResponse.ImportedLedgerEntry existingImportResponse(
            UUID clientEntryId, LedgerEntry existing, String payloadHash) {
        if (!Objects.equals(existing.getClientPayloadHash(), payloadHash)) {
            throwImportConflict();
        }
        return new ImportLedgerEntriesResponse.ImportedLedgerEntry(clientEntryId, LedgerEntryResponse.from(existing));
    }

    private void validateUniqueClientEntryIds(List<ImportLedgerEntriesRequest.ImportLedgerEntryItem> entries) {
        Set<UUID> seen = new HashSet<>();
        for (ImportLedgerEntriesRequest.ImportLedgerEntryItem item : entries) {
            if (!seen.add(item.clientEntryId())) {
                throwImportConflict();
            }
        }
    }

    private static String calculateClientPayloadHash(ImportLedgerEntriesRequest.ImportLedgerEntryItem item) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateDigest(digest, normalizeAmount(item.amount()));
            updateDigest(digest, item.currencyCode().name());
            updateDigest(digest, item.categoryId().toString());
            updateDigest(digest, item.assetId().toString());
            updateDigest(digest, item.transactionDate().toString());
            updateDigest(digest, LedgerEntry.normalizeMemo(item.memo()));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm is unavailable", e);
        }
    }

    private static String normalizeAmount(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }

    private static void updateDigest(MessageDigest digest, String value) {
        byte[] bytes = value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
        String prefix = value == null ? "-1:" : bytes.length + ":";
        digest.update(prefix.getBytes(StandardCharsets.UTF_8));
        digest.update(bytes);
        digest.update((byte) 0);
    }

    private static void throwImportConflict() {
        throw importConflict();
    }

    private static BusinessException importConflict() {
        return new BusinessException(LedgerErrorCode.LEDGER_IMPORT_CONFLICT);
    }
}

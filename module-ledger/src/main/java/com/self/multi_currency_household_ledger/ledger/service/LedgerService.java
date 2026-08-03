package com.self.multi_currency_household_ledger.ledger.service;

import com.self.multi_currency_household_ledger.common.exception.BusinessException;
import com.self.multi_currency_household_ledger.common.exception.DatabaseConstraints;
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
import com.self.multi_currency_household_ledger.ledger.dto.LedgerChangesResponse;
import com.self.multi_currency_household_ledger.ledger.dto.LedgerEntryResponse;
import com.self.multi_currency_household_ledger.ledger.dto.LedgerMonthlySummaryResponse;
import com.self.multi_currency_household_ledger.ledger.dto.LedgerReportResponse;
import com.self.multi_currency_household_ledger.ledger.dto.LedgerRestoreResponse;
import com.self.multi_currency_household_ledger.ledger.dto.SyncLedgerEntryRequest;
import com.self.multi_currency_household_ledger.ledger.dto.SyncLedgerEntryResponse;
import com.self.multi_currency_household_ledger.ledger.exception.LedgerErrorCode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class LedgerService {

    private static final int MONTHLY_ENTRY_LIMIT = 500;
    private static final int RESTORE_PAGE_SIZE_LIMIT = 500;
    private static final int CHANGES_PAGE_SIZE_LIMIT = 500;

    private final LedgerEntryRepository ledgerEntryRepository;
    private final CategoryRepository categoryRepository;
    private final AssetRepository assetRepository;
    private final ExchangeRateService exchangeRateService;
    private final Clock clock;
    private final LedgerSyncInsertService ledgerSyncInsertService;
    private final LedgerQuotaPolicy ledgerQuotaPolicy;

    @Transactional
    public LedgerEntryResponse create(CreateLedgerEntryRequest request, UUID memberId) {
        ledgerQuotaPolicy.assertCanCreate(memberId, 1);

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

        // 기존 행을 한 번에 읽어 두고 그 결과로 쿼터까지 판정한다 — 항목마다 조회하면 요청 1건이 항목 수만큼
        // 왕복하며 커넥션을 붙잡아, 동시 몇 건만으로 풀이 고갈되고 조회 요청까지 대기에 걸린다.
        Map<UUID, LedgerEntry> existingEntries = findExistingEntries(request.entries(), memberId);
        ledgerQuotaPolicy.assertCanCreate(memberId, request.entries().size() - existingEntries.size());

        List<ImportLedgerEntriesResponse.ImportedLedgerEntry> entries =
                new ArrayList<>(request.entries().size());
        // 항목마다 flush 하면 그때마다 영속성 컨텍스트 전체를 훑는다. 배치 끝에 한 번만 밀어낸다.
        // id 가 IDENTITY 라 insert 자체는 save() 시점에 나가므로, unique 경합은 루프 안에서도 밖에서도
        // 터질 수 있다 — 둘을 같은 try 로 묶어 어디서 나든 배치 전체를 롤백하고 409 로 매핑한다.
        try {
            for (ImportLedgerEntriesRequest.ImportLedgerEntryItem item : request.entries()) {
                entries.add(importEntry(memberId, item, existingEntries.get(item.clientEntryId())));
            }
            ledgerEntryRepository.flush();
        } catch (DataIntegrityViolationException e) {
            if (DatabaseConstraints.isLedgerEntryMemberForeignKeyViolation(e)) {
                throw e;
            }
            throw importConflict();
        }
        return new ImportLedgerEntriesResponse(entries);
    }

    @Transactional
    public SyncLedgerEntryResponse sync(SyncLedgerEntryRequest request, UUID memberId) {
        LedgerEntry entry = ledgerEntryRepository
                .findByMemberIdAndClientEntryId(memberId, request.clientEntryId())
                .map(existing -> replaceSyncedEntry(existing, request))
                .orElseGet(() -> createSyncedEntry(memberId, request));
        return SyncLedgerEntryResponse.from(request.clientEntryId(), entry);
    }

    @Transactional
    public void deleteSyncedEntry(UUID clientEntryId, UUID memberId) {
        ledgerEntryRepository.deleteByMemberIdAndClientEntryId(memberId, clientEntryId);
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
    public LedgerRestoreResponse restore(UUID memberId, LocalDate cursorDate, Long cursorId, int size) {
        validateRestoreCursor(cursorDate, cursorId);
        int pageSize = Math.max(1, Math.min(size, RESTORE_PAGE_SIZE_LIMIT));
        // hasNext 판별용 1건 lookahead만 추가로 조회하고 응답은 pageSize 이하로 자른다.
        PageRequest pageRequest = PageRequest.of(0, pageSize + 1);
        List<LedgerEntry> entries = cursorDate == null
                ? ledgerEntryRepository.findRestoreFirstPageByMemberId(memberId, pageRequest)
                : ledgerEntryRepository.findRestorePageByMemberIdAfterCursor(
                        memberId, cursorDate, cursorId, pageRequest);
        boolean hasNext = entries.size() > pageSize;
        List<LedgerEntry> pageEntries = hasNext ? entries.subList(0, pageSize) : entries;

        return LedgerRestoreResponse.from(pageEntries, hasNext);
    }

    @Transactional(readOnly = true)
    public LedgerChangesResponse getChanges(UUID memberId, LocalDateTime cursorUpdatedAt, Long cursorId, int size) {
        validateChangesCursor(cursorUpdatedAt, cursorId);
        int pageSize = Math.max(1, Math.min(size, CHANGES_PAGE_SIZE_LIMIT));
        PageRequest pageRequest = PageRequest.of(0, pageSize + 1);
        List<LedgerEntry> entries = cursorUpdatedAt == null
                ? ledgerEntryRepository.findChangesFirstPageByMemberId(memberId, pageRequest)
                : ledgerEntryRepository.findChangesPageByMemberIdAfterCursor(
                        memberId, cursorUpdatedAt, cursorId, pageRequest);
        boolean hasMore = entries.size() > pageSize;
        List<LedgerEntry> pageEntries = hasMore ? entries.subList(0, pageSize) : entries;

        return LedgerChangesResponse.from(pageEntries, hasMore);
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

    private static void validateRestoreCursor(LocalDate cursorDate, Long cursorId) {
        if ((cursorDate == null) != (cursorId == null) || (cursorId != null && cursorId <= 0)) {
            throw new BusinessException(LedgerErrorCode.INVALID_RESTORE_CURSOR);
        }
    }

    private static void validateChangesCursor(LocalDateTime cursorUpdatedAt, Long cursorId) {
        if ((cursorUpdatedAt == null) != (cursorId == null) || (cursorId != null && cursorId <= 0)) {
            throw new BusinessException(LedgerErrorCode.INVALID_CHANGES_CURSOR);
        }
    }

    private record DateRange(LocalDate startDate, LocalDate endDate) {

        private static DateRange of(int year, int month) {
            YearMonth yearMonth = YearMonth.of(year, month);
            LocalDate startDate = yearMonth.atDay(1);
            return new DateRange(startDate, yearMonth.plusMonths(1).atDay(1));
        }
    }

    private ImportLedgerEntriesResponse.ImportedLedgerEntry importEntry(
            UUID memberId, ImportLedgerEntriesRequest.ImportLedgerEntryItem item, LedgerEntry existing) {
        String payloadHash = calculateClientPayloadHash(item);
        return existing != null
                ? existingImportResponse(item.clientEntryId(), existing, payloadHash)
                : createImportedEntry(memberId, item, payloadHash);
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

        LedgerEntry saved = ledgerEntryRepository.save(entry);
        return new ImportLedgerEntriesResponse.ImportedLedgerEntry(
                item.clientEntryId(), LedgerEntryResponse.from(saved));
    }

    private ImportLedgerEntriesResponse.ImportedLedgerEntry existingImportResponse(
            UUID clientEntryId, LedgerEntry existing, String payloadHash) {
        if (!Objects.equals(existing.getClientPayloadHash(), payloadHash)) {
            throwImportConflict();
        }
        return new ImportLedgerEntriesResponse.ImportedLedgerEntry(clientEntryId, LedgerEntryResponse.from(existing));
    }

    private LedgerEntry createSyncedEntry(UUID memberId, SyncLedgerEntryRequest request) {
        ledgerQuotaPolicy.assertCanCreate(memberId, 1);
        try {
            return ledgerSyncInsertService.create(memberId, request);
        } catch (DataIntegrityViolationException e) {
            return ledgerEntryRepository
                    .findByMemberIdAndClientEntryId(memberId, request.clientEntryId())
                    .map(existing -> replaceSyncedEntry(existing, request))
                    .orElseThrow(() -> e);
        }
    }

    private LedgerEntry replaceSyncedEntry(LedgerEntry entry, SyncLedgerEntryRequest request) {
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
        return entry;
    }

    /** 요청에 들어온 clientEntryId 중 이미 존재하는 행을 한 번의 쿼리로 모아 온다. 신규 생성분 = 요청 수 − 이 결과 수. */
    private Map<UUID, LedgerEntry> findExistingEntries(
            List<ImportLedgerEntriesRequest.ImportLedgerEntryItem> entries, UUID memberId) {
        Set<UUID> requestedIds = entries.stream()
                .map(ImportLedgerEntriesRequest.ImportLedgerEntryItem::clientEntryId)
                .collect(Collectors.toSet());
        return ledgerEntryRepository.findByMemberIdAndClientEntryIdIn(memberId, requestedIds).stream()
                .collect(Collectors.toMap(LedgerEntry::getClientEntryId, entry -> entry));
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

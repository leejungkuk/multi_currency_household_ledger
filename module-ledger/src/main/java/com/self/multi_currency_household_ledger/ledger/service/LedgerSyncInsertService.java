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
import com.self.multi_currency_household_ledger.ledger.dto.SyncLedgerEntryRequest;
import com.self.multi_currency_household_ledger.ledger.exception.LedgerErrorCode;
import java.time.Clock;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
class LedgerSyncInsertService {

    private final LedgerEntryRepository ledgerEntryRepository;
    private final CategoryRepository categoryRepository;
    private final AssetRepository assetRepository;
    private final ExchangeRateService exchangeRateService;
    private final Clock clock;

    // LedgerService 전용 내부 헬퍼라 package-private 로 캡슐화한다.
    // 이 분리의 목적은 REQUIRES_NEW 별도 트랜잭션 — insert 가 부분 unique 를 위반해도 외부 sync()
    // 트랜잭션을 rollback-only 로 오염시키지 않고, createSyncedEntry 의 재조회→교체로 수렴시키기 위함.
    // package-private 메서드에서도 REQUIRES_NEW 가 실제로 별도 트랜잭션을 여는 런타임 동작은
    // LedgerSyncServiceIntegrationTest 의 커밋 독립성/경합 회복 테스트가 보증한다.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    LedgerEntry create(UUID memberId, SyncLedgerEntryRequest request) {
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
        entry.assignClientEntry(request.clientEntryId());
        // saveAndFlush: 부분 unique 위반을 이 시점에 DataIntegrityViolationException 으로 즉시 표면화해
        // createSyncedEntry 의 경합 fallback 으로 넘긴다(지연 flush 면 catch 밖에서 터진다 — save 로 바꾸지 말 것).
        return ledgerEntryRepository.saveAndFlush(entry);
    }
}

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
import com.self.multi_currency_household_ledger.ledger.dto.CreateLedgerEntryRequest;
import com.self.multi_currency_household_ledger.ledger.dto.LedgerEntryResponse;
import com.self.multi_currency_household_ledger.ledger.exception.LedgerErrorCode;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class LedgerService {

    private final LedgerEntryRepository ledgerEntryRepository;
    private final CategoryRepository categoryRepository;
    private final AssetRepository assetRepository;
    private final ExchangeRateService exchangeRateService;

    @Transactional
    public LedgerEntryResponse create(CreateLedgerEntryRequest request, UUID memberId) {
        Category category = categoryRepository
                .findByIdAndOwnerMemberId(request.categoryId(), Category.SYSTEM_OWNER_ID)
                .orElseThrow(() -> new BusinessException(LedgerErrorCode.CATEGORY_NOT_FOUND));

        Asset asset = assetRepository
                .findByIdAndOwnerMemberId(request.assetId(), Asset.SYSTEM_OWNER_ID)
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
                exchangeRate);

        LedgerEntry saved = ledgerEntryRepository.save(entry);
        return LedgerEntryResponse.from(saved);
    }
}

package com.self.multi_currency_household_ledger.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import com.self.multi_currency_household_ledger.exchange.domain.CurrencyCode;
import com.self.multi_currency_household_ledger.exchange.domain.ExchangeRate;
import com.self.multi_currency_household_ledger.exchange.service.ExchangeRateService;
import com.self.multi_currency_household_ledger.ledger.domain.Asset;
import com.self.multi_currency_household_ledger.ledger.domain.AssetRepository;
import com.self.multi_currency_household_ledger.ledger.domain.Category;
import com.self.multi_currency_household_ledger.ledger.domain.CategoryRepository;
import com.self.multi_currency_household_ledger.ledger.domain.LedgerEntry;
import com.self.multi_currency_household_ledger.ledger.domain.LedgerEntryRepository;
import com.self.multi_currency_household_ledger.ledger.domain.TransactionType;
import com.self.multi_currency_household_ledger.ledger.dto.SyncLedgerEntryRequest;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LedgerSyncInsertServiceTest {

    private static final UUID MEMBER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID CLIENT_ENTRY_ID = UUID.fromString("10000000-0000-0000-0000-000000000101");
    private static final LocalDate TODAY = LocalDate.of(2026, 4, 6);
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-04-05T15:00:00Z"), ZoneId.of("Asia/Seoul"));

    private LedgerSyncInsertService ledgerSyncInsertService;

    @Mock
    private LedgerEntryRepository ledgerEntryRepository;

    @Mock
    private CategoryRepository categoryRepository;

    @Mock
    private AssetRepository assetRepository;

    @Mock
    private ExchangeRateService exchangeRateService;

    private Category category;
    private Asset asset;

    @BeforeEach
    void setUp() {
        ledgerSyncInsertService = new LedgerSyncInsertService(
                ledgerEntryRepository, categoryRepository, assetRepository, exchangeRateService, FIXED_CLOCK);
        category = new Category(TransactionType.EXPENSE, "FOOD_DINING", "식비", "Food & Dining", "utensils", 1);
        asset = new Asset("CASH", "현금", "Cash", 3);
    }

    @Test
    @DisplayName("sync 신규 insert는 서버 환율로 재해석한 엔티티를 clientEntryId로 식별해 저장한다")
    void create_saves_new_synced_entry_with_server_rate() {
        SyncLedgerEntryRequest request = new SyncLedgerEntryRequest(
                CLIENT_ENTRY_ID, new BigDecimal("100.00"), CurrencyCode.USD, 1L, 3L, TODAY, "  점심  ");
        ExchangeRate exchangeRate = ExchangeRate.of(CurrencyCode.USD, new BigDecimal("1320.000000"), TODAY);
        given(categoryRepository.findById(1L)).willReturn(Optional.of(category));
        given(assetRepository.findById(3L)).willReturn(Optional.of(asset));
        given(exchangeRateService.getRateOnOrBefore(CurrencyCode.USD, TODAY)).willReturn(exchangeRate);
        given(ledgerEntryRepository.saveAndFlush(any(LedgerEntry.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        LedgerEntry saved = ledgerSyncInsertService.create(MEMBER_ID, request);

        assertThat(saved.getClientEntryId()).isEqualTo(CLIENT_ENTRY_ID);
        assertThat(saved.getClientPayloadHash()).isNull();
        assertThat(saved.getAppliedRate()).isEqualByComparingTo(new BigDecimal("1320.000000"));
        assertThat(saved.getKrwAmount()).isEqualByComparingTo(new BigDecimal("132000.00"));
        assertThat(saved.getMemo()).isEqualTo("점심");
        ArgumentCaptor<LedgerEntry> entryCaptor = ArgumentCaptor.forClass(LedgerEntry.class);
        then(ledgerEntryRepository).should().saveAndFlush(entryCaptor.capture());
        assertThat(entryCaptor.getValue().getMemberId()).isEqualTo(MEMBER_ID);
    }
}

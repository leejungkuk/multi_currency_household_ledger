package com.self.multi_currency_household_ledger.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;

import com.self.multi_currency_household_ledger.exchange.domain.CurrencyCode;
import com.self.multi_currency_household_ledger.exchange.service.ExchangeRateService;
import com.self.multi_currency_household_ledger.ledger.TestJpaConfig;
import com.self.multi_currency_household_ledger.ledger.TestLedgerApplication;
import com.self.multi_currency_household_ledger.ledger.domain.LedgerEntryRepository;
import com.self.multi_currency_household_ledger.ledger.dto.LedgerRestoreResponse;
import com.self.multi_currency_household_ledger.ledger.dto.SyncLedgerEntryRequest;
import com.self.multi_currency_household_ledger.ledger.dto.SyncLedgerEntryResponse;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@Import({
    TestLedgerApplication.class,
    TestJpaConfig.class,
    LedgerService.class,
    LedgerSyncInsertService.class,
    LedgerRestoreServiceIntegrationTest.ClockConfig.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class LedgerRestoreServiceIntegrationTest {

    private static final UUID MEMBER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OTHER_MEMBER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @MockitoBean
    @SuppressWarnings("UnusedVariable")
    private ExchangeRateService exchangeRateService;

    @BeforeEach
    void setUp() {
        ledgerEntryRepository.deleteAll();
    }

    @Test
    @DisplayName("restore는 member_id로 격리된 전량을 keyset으로 중복/누락 없이 반환하고 clientEntryId를 inline 포함한다")
    void restore_returns_all_member_entries_with_keyset_without_duplicates_or_omissions() {
        SyncLedgerEntryResponse oldest =
                sync(MEMBER_ID, "10000000-0000-0000-0000-000000000301", LocalDate.of(2026, 4, 3), "가장 오래됨");
        SyncLedgerEntryResponse sameDateFirst =
                sync(MEMBER_ID, "10000000-0000-0000-0000-000000000302", LocalDate.of(2026, 4, 5), "같은 날짜 먼저");
        SyncLedgerEntryResponse sameDateLater =
                sync(MEMBER_ID, "10000000-0000-0000-0000-000000000303", LocalDate.of(2026, 4, 5), "같은 날짜 나중");
        SyncLedgerEntryResponse newest =
                sync(MEMBER_ID, "10000000-0000-0000-0000-000000000304", LocalDate.of(2026, 4, 6), "최신");
        SyncLedgerEntryResponse middle =
                sync(MEMBER_ID, "10000000-0000-0000-0000-000000000305", LocalDate.of(2026, 4, 4), "중간");
        SyncLedgerEntryResponse otherMember =
                sync(OTHER_MEMBER_ID, "10000000-0000-0000-0000-000000000306", LocalDate.of(2026, 4, 7), "타 회원");
        Map<Long, UUID> expectedClientEntryIds = Map.of(
                oldest.ledgerEntry().id(), oldest.clientEntryId(),
                sameDateFirst.ledgerEntry().id(), sameDateFirst.clientEntryId(),
                sameDateLater.ledgerEntry().id(), sameDateLater.clientEntryId(),
                newest.ledgerEntry().id(), newest.clientEntryId(),
                middle.ledgerEntry().id(), middle.clientEntryId());

        List<LedgerRestoreResponse.RestoredLedgerEntry> restored = new ArrayList<>();
        List<Integer> pageSizes = new ArrayList<>();
        LedgerRestoreResponse page = ledgerService.restore(MEMBER_ID, null, null, 2);
        while (true) {
            pageSizes.add(page.entries().size());
            restored.addAll(page.entries());

            if (!page.hasNext()) {
                assertThat(page.nextCursor()).isNull();
                break;
            }

            assertThat(page.nextCursor()).isNotNull();
            page = ledgerService.restore(
                    MEMBER_ID,
                    page.nextCursor().transactionDate(),
                    page.nextCursor().id(),
                    2);
        }

        assertThat(pageSizes).containsExactly(2, 2, 1);
        assertThat(restored)
                .extracting(LedgerRestoreResponse.RestoredLedgerEntry::id)
                .containsExactly(
                        newest.ledgerEntry().id(),
                        sameDateLater.ledgerEntry().id(),
                        sameDateFirst.ledgerEntry().id(),
                        middle.ledgerEntry().id(),
                        oldest.ledgerEntry().id());
        assertThat(restored)
                .extracting(LedgerRestoreResponse.RestoredLedgerEntry::id)
                .doesNotHaveDuplicates()
                .doesNotContain(otherMember.ledgerEntry().id());
        assertThat(restored).allSatisfy(entry -> {
            assertThat(entry.clientEntryId()).isEqualTo(expectedClientEntryIds.get(entry.id()));
            assertThat(entry.appliedRate()).isEqualByComparingTo(BigDecimal.ONE);
        });

        LedgerRestoreResponse otherMemberRestore = ledgerService.restore(OTHER_MEMBER_ID, null, null, 500);
        assertThat(otherMemberRestore.entries()).singleElement().satisfies(entry -> {
            assertThat(entry.id()).isEqualTo(otherMember.ledgerEntry().id());
            assertThat(entry.clientEntryId()).isEqualTo(otherMember.clientEntryId());
            assertThat(entry.memo()).isEqualTo("타 회원");
        });
        assertThat(otherMemberRestore.hasNext()).isFalse();
    }

    private SyncLedgerEntryResponse sync(UUID memberId, String clientEntryId, LocalDate transactionDate, String memo) {
        return ledgerService.sync(
                new SyncLedgerEntryRequest(
                        UUID.fromString(clientEntryId),
                        new BigDecimal("1000.00"),
                        CurrencyCode.KRW,
                        1L,
                        3L,
                        transactionDate,
                        memo),
                memberId);
    }

    @TestConfiguration
    static class ClockConfig {

        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-04-05T15:00:00Z"), ZoneId.of("Asia/Seoul"));
        }
    }
}

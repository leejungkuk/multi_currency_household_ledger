package com.self.multi_currency_household_ledger.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;

import com.self.multi_currency_household_ledger.exchange.domain.CurrencyCode;
import com.self.multi_currency_household_ledger.exchange.domain.ExchangeRate;
import com.self.multi_currency_household_ledger.ledger.TestJpaConfig;
import com.self.multi_currency_household_ledger.ledger.TestLedgerApplication;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@Import({TestLedgerApplication.class, TestJpaConfig.class})
class LedgerEntryRepositoryTest {

    private static final UUID MEMBER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final LocalDate TODAY = LocalDate.of(2026, 4, 6);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-04-05T15:00:00Z"), KST);

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private AssetRepository assetRepository;

    private Category category;
    private Asset asset;

    @BeforeEach
    void setUp() {
        category = categoryRepository.save(new Category(TransactionType.EXPENSE, "FOOD", "식비", "icon-food", 1, 1L));
        asset = assetRepository.save(new Asset("CASH", "현금", "icon-cash", 1, 1L));
    }

    // 가계부 내역을 정상적으로 저장할 수 있는지 확인한다.
    @Test
    @DisplayName("가계부 내역을 저장하고 조회할 수 있다")
    void save_and_find_ledger_entry() {
        LedgerEntry entry = LedgerEntry.of(
                MEMBER_ID, category, asset, BigDecimal.valueOf(5000), CurrencyCode.KRW, TODAY, "커피", null, FIXED_CLOCK);

        LedgerEntry saved = ledgerEntryRepository.save(entry);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getMemberId()).isEqualTo(MEMBER_ID);
        assertThat(saved.getOriginalAmount()).isEqualByComparingTo(BigDecimal.valueOf(5000));
        assertThat(saved.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("member_id는 Postgres uuid 타입으로 저장된다")
    void save_ledger_entry_with_uuid_member_id() {
        LedgerEntry entry = LedgerEntry.of(
                MEMBER_ID, category, asset, BigDecimal.valueOf(5000), CurrencyCode.KRW, TODAY, "커피", null, FIXED_CLOCK);

        LedgerEntry saved = ledgerEntryRepository.saveAndFlush(entry);

        UUID storedMemberId = jdbcTemplate.queryForObject(
                "select member_id from ledger_entry where id = ?", UUID.class, saved.getId());
        String columnType = jdbcTemplate.queryForObject(
                """
                select udt_name
                from information_schema.columns
                where table_name = 'ledger_entry'
                  and column_name = 'member_id'
                """,
                String.class);
        assertThat(storedMemberId).isEqualTo(MEMBER_ID);
        assertThat(columnType).isEqualTo("uuid");
    }

    @Test
    @DisplayName("보정창 시작일 이후 외화 거래만 조회한다")
    void find_foreign_entries_on_or_after_correction_window() {
        LocalDate cutoff = TODAY.minusDays(7);
        ExchangeRate usdRate = ExchangeRate.of(CurrencyCode.USD, new BigDecimal("1300.000000"), cutoff);
        LedgerEntry staleForeign = LedgerEntry.of(
                MEMBER_ID,
                category,
                asset,
                new BigDecimal("100.00"),
                CurrencyCode.USD,
                cutoff,
                "보정창 내 외화",
                usdRate,
                FIXED_CLOCK);
        LedgerEntry krwEntry = LedgerEntry.of(
                MEMBER_ID,
                category,
                asset,
                new BigDecimal("5000.00"),
                CurrencyCode.KRW,
                TODAY,
                "원화",
                null,
                FIXED_CLOCK);
        LedgerEntry oldForeign = LedgerEntry.of(
                MEMBER_ID,
                category,
                asset,
                new BigDecimal("100.00"),
                CurrencyCode.USD,
                cutoff.minusDays(1),
                "보정창 밖 외화",
                usdRate,
                FIXED_CLOCK);

        ledgerEntryRepository.saveAll(List.of(staleForeign, krwEntry, oldForeign));
        ledgerEntryRepository.flush();

        List<LedgerEntry> entries = ledgerEntryRepository.findForeignEntriesOnOrAfter(cutoff);

        assertThat(entries).extracting(LedgerEntry::getMemo).containsExactly("보정창 내 외화");
    }
}

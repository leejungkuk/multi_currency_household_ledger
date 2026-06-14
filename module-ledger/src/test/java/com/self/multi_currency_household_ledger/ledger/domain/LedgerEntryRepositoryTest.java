package com.self.multi_currency_household_ledger.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;

import com.self.multi_currency_household_ledger.exchange.domain.CurrencyCode;
import com.self.multi_currency_household_ledger.ledger.TestJpaConfig;
import com.self.multi_currency_household_ledger.ledger.TestLedgerApplication;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
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
                MEMBER_ID,
                category,
                asset,
                BigDecimal.valueOf(5000),
                CurrencyCode.KRW,
                LocalDate.now(ZoneId.of("Asia/Seoul")),
                "커피",
                null);

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
                MEMBER_ID,
                category,
                asset,
                BigDecimal.valueOf(5000),
                CurrencyCode.KRW,
                LocalDate.now(ZoneId.of("Asia/Seoul")),
                "커피",
                null);

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
}

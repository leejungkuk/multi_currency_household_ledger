package com.self.multi_currency_household_ledger.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class LedgerEntryChecksTest {

    private static final UUID MEMBER_ID = UUID.fromString("00000000-0000-0000-0000-000000000502");

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine").withInitScript("testcontainers/auth-users-stub.sql");

    private static JdbcTemplate jdbcTemplate;

    @BeforeAll
    static void migrate() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUsername(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        Flyway.configure()
                .dataSource((DataSource) dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        jdbcTemplate = new JdbcTemplate(dataSource);
    }

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("delete from auth.users");
        jdbcTemplate.update("insert into auth.users (id) values (?)", MEMBER_ID);
    }

    // 거부 단언마다 제약 하나만 어기게 나머지 값은 규칙 안에 둔다 — 다른 제약이 먼저 걸려 통과하는 일이 없다.
    // 금액은 문자열로 넘겨 SQL 에서 numeric 으로 바꾼다 — BigDecimal 은 'NaN' 을 담지 못한다.

    @Test
    @DisplayName("거래 유형은 INCOME·EXPENSE 만 받고 TRANSFER 는 ck_ledger_entry_transaction_type 으로 거부된다")
    void transaction_type_must_be_income_or_expense() {
        assertThatThrownBy(() -> insertEntry("TRANSFER", "1000.00", "1000.00", "1.000000"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_ledger_entry_transaction_type");

        insertEntry("INCOME", "1000.00", "1000.00", "1.000000");
        insertEntry("EXPENSE", "1000.00", "1000.00", "1.000000");

        assertThat(entryCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("원 금액은 0 초과 99,999,999 이하만 받고 0·음수·1억·NaN 은 ck_ledger_entry_original_amount 로 거부된다")
    void original_amount_must_be_positive_and_at_most_99999999() {
        assertThatThrownBy(() -> insertEntry("EXPENSE", "0", "1000.00", "1.000000"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_ledger_entry_original_amount");
        assertThatThrownBy(() -> insertEntry("EXPENSE", "-0.01", "1000.00", "1.000000"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_ledger_entry_original_amount");
        assertThatThrownBy(() -> insertEntry("EXPENSE", "100000000.00", "1000.00", "1.000000"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_ledger_entry_original_amount");
        assertThatThrownBy(() -> insertEntry("EXPENSE", "NaN", "1000.00", "1.000000"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_ledger_entry_original_amount");

        insertEntry("EXPENSE", "0.01", "1000.00", "1.000000");
        insertEntry("EXPENSE", "99999999.00", "1000.00", "1.000000");

        assertThat(entryCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("원화 금액은 음수·NaN 이면 ck_ledger_entry_krw_amount 로 거부되고 0.00 은 통과한다")
    void krw_amount_must_not_be_negative() {
        assertThatThrownBy(() -> insertEntry("EXPENSE", "1000.00", "-0.01", "1.000000"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_ledger_entry_krw_amount");
        assertThatThrownBy(() -> insertEntry("EXPENSE", "1000.00", "NaN", "1.000000"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_ledger_entry_krw_amount");

        insertEntry("EXPENSE", "0.01", "0.00", "1.000000");

        assertThat(entryCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("적용 환율은 0·NaN 이면 ck_ledger_entry_applied_rate 로 거부되고 0.000001 은 통과한다")
    void applied_rate_must_be_positive() {
        assertThatThrownBy(() -> insertEntry("EXPENSE", "1000.00", "1000.00", "0"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_ledger_entry_applied_rate");
        assertThatThrownBy(() -> insertEntry("EXPENSE", "1000.00", "1000.00", "NaN"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_ledger_entry_applied_rate");

        insertEntry("EXPENSE", "1000.00", "1000.00", "0.000001");

        assertThat(entryCount()).isEqualTo(1);
    }

    private void insertEntry(String transactionType, String originalAmount, String krwAmount, String appliedRate) {
        jdbcTemplate.update(
                """
                insert into ledger_entry (member_id, transaction_type, category_id, asset_id, original_amount,
                    currency_code, applied_rate, krw_amount, transaction_date, created_at, updated_at)
                values (?, ?, 1, 1, ?::numeric, 'KRW', ?::numeric, ?::numeric, '2026-10-01', now(), now())
                """,
                MEMBER_ID,
                transactionType,
                originalAmount,
                appliedRate,
                krwAmount);
    }

    private int entryCount() {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from ledger_entry where member_id = ?", Integer.class, MEMBER_ID);
        return count == null ? 0 : count;
    }
}

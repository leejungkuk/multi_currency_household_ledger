package com.self.multi_currency_household_ledger.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** V14 상태에 행을 넣고 V15 를 올린다 — 한 컨테이너를 쓰므로 V14 행은 {@link #migrateV14RowsToV15()} 에서 한 번만 넣는다. */
@Testcontainers
class BudgetMonthOnlyMigrationTest {

    private static final String MIGRATION_LOCATION = "classpath:db/migration";
    private static final UUID MEMBER = UUID.fromString("00000000-0000-0000-0000-000000000501");
    private static final UUID MEMBER_B = UUID.fromString("00000000-0000-0000-0000-000000000502");
    private static final UUID MEMBER_C = UUID.fromString("00000000-0000-0000-0000-000000000503");
    private static final long FOOD = 1L;
    private static final long SALARY = 14L;

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine").withInitScript("testcontainers/auth-users-stub.sql");

    private static JdbcTemplate jdbcTemplate;
    private static long expenseMonthId;
    private static long expenseDefaultId;
    private static long incomeMonthId;
    private static long offMonthId;

    @BeforeAll
    static void migrateV14RowsToV15() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUsername(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        jdbcTemplate = new JdbcTemplate(dataSource);

        migrateToVersion(dataSource, "14");
        for (UUID member : new UUID[] {MEMBER, MEMBER_B, MEMBER_C}) {
            jdbcTemplate.update("insert into auth.users (id) values (?)", member);
        }
        expenseMonthId = insertV14Budget("EXPENSE", "MONTH", "2026-09-01", "'KRW'", "1000.00");
        insertGroupAllocation(expenseMonthId);
        insertCategoryAllocation(expenseMonthId, FOOD);
        expenseDefaultId = insertV14Budget("EXPENSE", "DEFAULT", "2026-09-01", "'KRW'", "2000.00");
        insertGroupAllocation(expenseDefaultId);
        insertCategoryAllocation(expenseDefaultId, FOOD);
        incomeMonthId = insertV14Budget("INCOME", "MONTH", "2026-09-01", "'KRW'", "3000.00");
        insertCategoryAllocation(incomeMonthId, SALARY);
        offMonthId = insertV14Budget("EXPENSE", "MONTH", "2026-10-01", "null", "null");
        insertGroupAllocation(offMonthId);

        migrateToVersion(dataSource, "15");
    }

    @Test
    @DisplayName("V15 는 (지출, 달, 금액 있음) 행과 그 몫만 남기고, 기본값·수입·끔 행의 몫은 cascade 로 지운다")
    void v15_keeps_only_expense_month_rows_with_amounts() {
        assertThat(jdbcTemplate.queryForList("select id from budget where member_id = ?", Long.class, MEMBER))
                .containsExactly(expenseMonthId);
        Map<String, Object> kept = jdbcTemplate.queryForMap(
                "select cast(month as text) as month, currency_code, total_amount from budget where id = ?",
                expenseMonthId);
        assertThat(kept.get("month")).isEqualTo("2026-09-01");
        assertThat(kept.get("currency_code")).isEqualTo("KRW");
        assertThat(kept.get("total_amount")).asString().isEqualTo("1000.00");

        assertThat(count("select count(*) from budget_allocation where budget_id = ?", expenseMonthId))
                .isEqualTo(2);
        assertThat(count(
                        "select count(*) from budget_allocation where budget_id in (?, ?, ?)",
                        expenseDefaultId,
                        incomeMonthId,
                        offMonthId))
                .isZero();
        assertThat(jdbcTemplate.queryForList(
                        """
                        select column_name from information_schema.columns
                        where table_name = 'budget' and column_name in ('axis', 'kind')
                        """,
                        String.class))
                .isEmpty();
    }

    @Test
    @DisplayName("V15 뒤 같은 회원·같은 달 두 번째 행은 uk_budget 위반이고, 다른 회원의 같은 달은 된다")
    void v15_rejects_second_row_for_same_member_month() {
        insertBudget(MEMBER_B, "2026-11-01", "'KRW'", "1000.00");

        assertThatThrownBy(() -> insertBudget(MEMBER_B, "2026-11-01", "'USD'", "10.00"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_budget");
        insertBudget(MEMBER_C, "2026-11-01", "'KRW'", "1000.00");

        assertThat(count("select count(*) from budget where month = date '2026-11-01'"))
                .isEqualTo(2);
    }

    @Test
    @DisplayName("V15 뒤 통화나 금액이 null 인 행은 거부된다")
    void v15_requires_currency_and_total() {
        assertThatThrownBy(() -> insertBudget(MEMBER_C, "2026-12-01", "null", "1000.00"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("currency_code");
        assertThatThrownBy(() -> insertBudget(MEMBER_C, "2027-01-01", "'KRW'", "null"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("total_amount");
    }

    private static void migrateToVersion(DataSource dataSource, String version) {
        Flyway.configure()
                .dataSource(dataSource)
                .locations(MIGRATION_LOCATION)
                .target(MigrationVersion.fromVersion(version))
                .load()
                .migrate();
    }

    private static long insertV14Budget(String axis, String kind, String month, String currencySql, String totalSql) {
        Long id = jdbcTemplate.queryForObject(
                "insert into budget (member_id, axis, kind, month, currency_code, total_amount)"
                        + " values (?, ?, ?, ?::date, " + currencySql + ", " + totalSql + ") returning id",
                Long.class,
                MEMBER,
                axis,
                kind,
                month);
        return id == null ? 0 : id;
    }

    private static void insertBudget(UUID memberId, String month, String currencySql, String totalSql) {
        jdbcTemplate.update(
                "insert into budget (member_id, month, currency_code, total_amount)" + " values (?, ?::date, "
                        + currencySql + ", " + totalSql + ")",
                memberId,
                month);
    }

    private static void insertGroupAllocation(long budgetId) {
        jdbcTemplate.update(
                "insert into budget_allocation (budget_id, payment_group, amount) values (?, 'CREDIT_CARD', 100.00)",
                budgetId);
    }

    private static void insertCategoryAllocation(long budgetId, long categoryId) {
        jdbcTemplate.update(
                "insert into budget_allocation (budget_id, category_id, amount) values (?, ?, 100.00)",
                budgetId,
                categoryId);
    }

    private static int count(String sql, Object... args) {
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return count == null ? 0 : count;
    }
}

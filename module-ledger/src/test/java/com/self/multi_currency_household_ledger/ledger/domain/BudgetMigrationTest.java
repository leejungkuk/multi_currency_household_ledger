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
class BudgetMigrationTest {

    private static final UUID MEMBER_ID = UUID.fromString("00000000-0000-0000-0000-000000000401");

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

    @Test
    @DisplayName("통화나 금액이 없는 행은 거부된다")
    void currency_or_total_null_is_rejected() {
        assertThatThrownBy(() -> insertBudget("2026-09-01", "'USD'", "null"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("total_amount");
        assertThatThrownBy(() -> insertBudget("2026-09-01", "null", "100.00"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("currency_code");
    }

    @Test
    @DisplayName("통화·금액이 둘 다 있으면 저장된다")
    void currency_and_total_together_are_accepted() {
        insertBudget("2026-09-01", "'USD'", "100.00");
        insertBudget("2026-10-01", "'KRW'", "0");

        assertThat(count("select count(*) from budget where member_id = ?", MEMBER_ID))
                .isEqualTo(2);
    }

    @Test
    @DisplayName("month 는 1일만 받는다")
    void month_must_be_first_day() {
        assertThatThrownBy(() -> insertBudget("2026-09-02", "'KRW'", "1000.00"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("budget 을 지우면 allocation 이 DB cascade 로 사라진다")
    void deleting_budget_cascades_allocations() {
        long budgetId = insertBudget("2026-09-01", "'KRW'", "1000.00");
        jdbcTemplate.update(
                "insert into budget_allocation (budget_id, payment_group, amount) values (?, 'CREDIT_CARD', 500.00)",
                budgetId);

        jdbcTemplate.update("delete from budget where id = ?", budgetId);

        assertThat(count("select count(*) from budget_allocation where budget_id = ?", budgetId))
                .isZero();
    }

    @Test
    @DisplayName("몫은 결제수단 그룹과 카테고리 중 정확히 하나를 가리킨다")
    void allocation_targets_exactly_one_of_group_or_category() {
        long budgetId = insertBudget("2026-09-01", "'KRW'", "1000.00");

        assertThatThrownBy(() -> jdbcTemplate.update(
                        "insert into budget_allocation (budget_id, amount) values (?, 500.00)", budgetId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_budget_allocation_target");
        assertThatThrownBy(() -> jdbcTemplate.update(
                        "insert into budget_allocation (budget_id, payment_group, category_id, amount)"
                                + " values (?, 'CREDIT_CARD', 1, 500.00)",
                        budgetId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_budget_allocation_target");
    }

    @Test
    @DisplayName("커스텀 카테고리에 몫이 있는 회원의 auth.users 삭제가 성공하고 budget·allocation·category 가 0행이다")
    void deleting_auth_user_with_custom_category_allocation_succeeds() {
        Long categoryId = jdbcTemplate.queryForObject(
                """
                insert into category (transaction_type, code, display_name_ko, display_name_en, owner_member_id)
                values ('EXPENSE', 'CUSTOM', '반려견', '반려견', ?)
                returning id
                """,
                Long.class,
                MEMBER_ID);
        long budgetId = insertBudget("2026-09-01", "'KRW'", "1000.00");
        jdbcTemplate.update(
                "insert into budget_allocation (budget_id, category_id, amount) values (?, ?, 500.00)",
                budgetId,
                categoryId);

        jdbcTemplate.update("delete from auth.users where id = ?", MEMBER_ID);

        assertThat(count("select count(*) from budget where member_id = ?", MEMBER_ID))
                .isZero();
        assertThat(count("select count(*) from budget_allocation where budget_id = ?", budgetId))
                .isZero();
        assertThat(count("select count(*) from category where owner_member_id = ?", MEMBER_ID))
                .isZero();
    }

    private long insertBudget(String month, String currencySql, String totalSql) {
        Long id = jdbcTemplate.queryForObject(
                "insert into budget (member_id, month, currency_code, total_amount)"
                        + " values (?, ?::date, " + currencySql + ", " + totalSql + ")"
                        + " returning id",
                Long.class,
                MEMBER_ID,
                month);
        return id == null ? 0 : id;
    }

    private int count(String sql, Object... args) {
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return count == null ? 0 : count;
    }
}

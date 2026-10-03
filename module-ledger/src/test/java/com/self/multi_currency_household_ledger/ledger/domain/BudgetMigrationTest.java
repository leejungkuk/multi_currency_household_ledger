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
    @DisplayName("budget 을 지우면 카테고리 몫이 DB cascade 로 사라진다")
    void deleting_budget_cascades_category_shares() {
        long budgetId = insertBudget("2026-09-01", "'KRW'", "1000.00");
        insertCategoryShare(budgetId, 1L, "500.00");

        jdbcTemplate.update("delete from budget where id = ?", budgetId);

        assertThat(count("select count(*) from budget_category_allocation where budget_id = ?", budgetId))
                .isZero();
    }

    @Test
    @DisplayName("결제수단 몫 합이 전체보다 크면 ck_budget_payment_groups_within_total 로 거부되고, 합 = 전체·전부 null 은 통과한다")
    void payment_group_shares_over_total_are_rejected() {
        assertThatThrownBy(() -> insertBudgetWithGroups("2026-09-01", "1000.00", "600.00", "300.00", "100.01"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_budget_payment_groups_within_total");

        insertBudgetWithGroups("2026-09-01", "1000.00", "600.00", "300.00", "100.00");
        insertBudgetWithGroups("2026-10-01", "1000.00", null, null, null);

        assertThat(count("select count(*) from budget where member_id = ?", MEMBER_ID))
                .isEqualTo(2);
    }

    @Test
    @DisplayName("결제수단 몫이 음수이거나 99,999,999 를 넘으면 거부되고, 0 과 99,999,999 는 통과한다")
    void payment_group_share_out_of_range_is_rejected() {
        // 예외 메시지에 INSERT 문이 실려 컬럼 이름은 항상 들어 있다 — Postgres 가 붙인 제약 이름으로 단언한다.
        assertThatThrownBy(() -> insertBudgetWithGroups("2026-09-01", "1000.00", "-0.01", null, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("budget_credit_card_amount_check");
        // 전체는 상한이 99,999,999 라 상한을 넘는 몫은 합계 제약도 어긴다. Postgres 는 CHECK 를 이름순으로 검사하므로
        // budget_* 컬럼 제약이 ck_budget_payment_groups_within_total 보다 먼저 걸린다 — 전체를 상한에 두어 합계 초과를 0.01 로 줄였다.
        assertThatThrownBy(() -> insertBudgetWithGroups("2026-09-01", "99999999.00", null, "99999999.01", null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("budget_cash_and_debit_amount_check");
        assertThatThrownBy(() -> insertBudgetWithGroups("2026-09-01", "99999999.00", null, null, "-1"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("budget_account_and_other_amount_check");

        insertBudgetWithGroups("2026-09-01", "99999999.00", "0", null, null);
        insertBudgetWithGroups("2026-10-01", "99999999.00", null, null, "99999999.00");

        assertThat(count("select count(*) from budget where member_id = ?", MEMBER_ID))
                .isEqualTo(2);
    }

    @Test
    @DisplayName("한 예산에 같은 카테고리 몫은 두 번 못 넣고, 같은 예산의 다른 카테고리 몫은 넣는다")
    void category_share_is_unique_per_budget_and_category() {
        long budgetId = insertBudget("2026-09-01", "'KRW'", "1000.00");
        insertCategoryShare(budgetId, 1L, "300.00");

        assertThatThrownBy(() -> insertCategoryShare(budgetId, 1L, "200.00"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("pk_budget_category_allocation");
        insertCategoryShare(budgetId, 2L, "200.00");

        assertThat(count("select count(*) from budget_category_allocation where budget_id = ?", budgetId))
                .isEqualTo(2);
    }

    @Test
    @DisplayName("카테고리 몫에는 category_id 로 시작하는 인덱스가 있다 — 카테고리 hard delete 가 테이블 전체를 훑지 않는다")
    void category_shares_are_indexed_by_category_id() {
        assertThat(jdbcTemplate.queryForList(
                        "select indexdef from pg_indexes where tablename = 'budget_category_allocation'", String.class))
                .anySatisfy(indexdef -> assertThat(indexdef).contains("(category_id"));
    }

    @Test
    @DisplayName("커스텀 카테고리에 몫이 있는 회원의 auth.users 삭제가 성공하고 budget·카테고리 몫·category 가 0행이다")
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
        insertCategoryShare(budgetId, categoryId, "500.00");

        jdbcTemplate.update("delete from auth.users where id = ?", MEMBER_ID);

        assertThat(count("select count(*) from budget where member_id = ?", MEMBER_ID))
                .isZero();
        assertThat(count("select count(*) from budget_category_allocation where budget_id = ?", budgetId))
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

    private void insertBudgetWithGroups(
            String month, String total, String creditCard, String cashAndDebit, String accountAndOther) {
        jdbcTemplate.update(
                """
                insert into budget (member_id, month, currency_code, total_amount,
                                    credit_card_amount, cash_and_debit_amount, account_and_other_amount)
                values (?, ?::date, 'KRW', ?::numeric, ?::numeric, ?::numeric, ?::numeric)
                """,
                MEMBER_ID,
                month,
                total,
                creditCard,
                cashAndDebit,
                accountAndOther);
    }

    private void insertCategoryShare(long budgetId, Long categoryId, String amount) {
        jdbcTemplate.update(
                "insert into budget_category_allocation (budget_id, category_id, amount) values (?, ?, ?::numeric)",
                budgetId,
                categoryId,
                amount);
    }

    private int count(String sql, Object... args) {
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return count == null ? 0 : count;
    }
}

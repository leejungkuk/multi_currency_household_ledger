package com.self.multi_currency_household_ledger.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** V15 상태에 행을 넣고 V16 을 올린다 — 한 컨테이너를 쓰므로 V15 행은 {@link #migrateV15RowsToV16()} 에서 한 번만 넣는다. */
@Testcontainers
class BudgetCategoryAllocationMigrationTest {

    private static final String MIGRATION_LOCATION = "classpath:db/migration";
    private static final UUID MEMBER_ALL_GROUPS = UUID.fromString("00000000-0000-0000-0000-000000000601");
    private static final UUID MEMBER_CARD_ONLY = UUID.fromString("00000000-0000-0000-0000-000000000602");
    private static final UUID MEMBER_NO_GROUP = UUID.fromString("00000000-0000-0000-0000-000000000603");
    private static final long FOOD = 1L;
    private static final long CAFE = 2L;

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine").withInitScript("testcontainers/auth-users-stub.sql");

    private static JdbcTemplate jdbcTemplate;
    private static long allGroupsId;
    private static long cardOnlyId;
    private static long noGroupId;

    @BeforeAll
    static void migrateV15RowsToV16() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUsername(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        jdbcTemplate = new JdbcTemplate(dataSource);

        migrateToVersion(dataSource, "15");
        for (UUID member : new UUID[] {MEMBER_ALL_GROUPS, MEMBER_CARD_ONLY, MEMBER_NO_GROUP}) {
            jdbcTemplate.update("insert into auth.users (id) values (?)", member);
        }
        allGroupsId = insertV15Budget(MEMBER_ALL_GROUPS, "1000.00");
        insertGroupShare(allGroupsId, "CREDIT_CARD", "300.00");
        insertGroupShare(allGroupsId, "CASH_AND_DEBIT", "200.00");
        insertGroupShare(allGroupsId, "ACCOUNT_AND_OTHER", "100.50");
        insertCategoryShare(allGroupsId, FOOD, "400.00");
        insertCategoryShare(allGroupsId, CAFE, "250.50");
        cardOnlyId = insertV15Budget(MEMBER_CARD_ONLY, "500.00");
        insertGroupShare(cardOnlyId, "CREDIT_CARD", "500.00");
        noGroupId = insertV15Budget(MEMBER_NO_GROUP, "800.00");
        insertCategoryShare(noGroupId, FOOD, "100.00");
        insertCategoryShare(noGroupId, CAFE, "0.00");

        migrateToVersion(dataSource, "16");
    }

    @Test
    @DisplayName("V16 은 결제수단 몫을 budget 의 세 컬럼에 같은 금액으로 옮기고, 몫이 없던 그룹은 null 로 둔다")
    void v16_moves_payment_group_shares_to_budget_columns() {
        assertThat(groupColumns(allGroupsId)).isEqualTo("300.00/200.00/100.50");
        assertThat(groupColumns(cardOnlyId)).isEqualTo("500.00/null/null");
        assertThat(groupColumns(noGroupId)).isEqualTo("null/null/null");
    }

    @Test
    @DisplayName("V16 은 카테고리 몫을 같은 금액으로 budget_category_allocation 에 옮기고 budget_allocation 을 지운다")
    void v16_copies_category_shares_with_same_amounts() {
        assertThat(jdbcTemplate.queryForList(
                        "select budget_id || ':' || category_id || '=' || amount from budget_category_allocation",
                        String.class))
                .containsExactlyInAnyOrder(
                        allGroupsId + ":" + FOOD + "=400.00",
                        allGroupsId + ":" + CAFE + "=250.50",
                        noGroupId + ":" + FOOD + "=100.00",
                        noGroupId + ":" + CAFE + "=0.00");
        assertThat(jdbcTemplate.queryForObject("select to_regclass('public.budget_allocation') is null", Boolean.class))
                .isTrue();
    }

    private static void migrateToVersion(DataSource dataSource, String version) {
        Flyway.configure()
                .dataSource(dataSource)
                .locations(MIGRATION_LOCATION)
                .target(MigrationVersion.fromVersion(version))
                .load()
                .migrate();
    }

    private static long insertV15Budget(UUID member, String total) {
        Long id = jdbcTemplate.queryForObject(
                "insert into budget (member_id, month, currency_code, total_amount)"
                        + " values (?, date '2026-09-01', 'KRW', ?::numeric) returning id",
                Long.class,
                member,
                total);
        return id == null ? 0 : id;
    }

    private static void insertGroupShare(long budgetId, String paymentGroup, String amount) {
        jdbcTemplate.update(
                "insert into budget_allocation (budget_id, payment_group, amount) values (?, ?, ?::numeric)",
                budgetId,
                paymentGroup,
                amount);
    }

    private static void insertCategoryShare(long budgetId, long categoryId, String amount) {
        jdbcTemplate.update(
                "insert into budget_allocation (budget_id, category_id, amount) values (?, ?, ?::numeric)",
                budgetId,
                categoryId,
                amount);
    }

    /** 신용카드/현금·체크카드/계좌·기타 몫 컬럼을 "/" 로 잇는다. null 은 "null". */
    private static String groupColumns(long budgetId) {
        return jdbcTemplate.queryForObject(
                """
                select coalesce(cast(credit_card_amount as text), 'null')
                       || '/' || coalesce(cast(cash_and_debit_amount as text), 'null')
                       || '/' || coalesce(cast(account_and_other_amount as text), 'null')
                from budget where id = ?
                """,
                String.class,
                budgetId);
    }
}

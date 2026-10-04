package com.self.multi_currency_household_ledger.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
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

/** V17 상태에 몫 행을 넣고 V18 을 올린다 — 한 컨테이너를 쓰므로 V17 행은 {@link #migrateV17RowsToV18()} 에서 한 번만 넣는다. */
@Testcontainers
class BudgetLineOrderMigrationTest {

    private static final String MIGRATION_LOCATION = "classpath:db/migration";
    private static final UUID MEMBER = UUID.fromString("00000000-0000-0000-0000-000000000701");
    // 시스템 지출 카테고리는 id = sort_order 다.
    private static final long FOOD = 1L;
    private static final long CAFE = 2L;
    private static final long SYSTEM_5 = 5L;
    private static final long SYSTEM_7 = 7L;

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine").withInitScript("testcontainers/auth-users-stub.sql");

    private static JdbcTemplate jdbcTemplate;
    private static long septemberId;
    private static long octoberId;
    private static long customFirst;
    private static long tieSmallerId;
    private static long tieLargerId;
    private static long inactiveCustom;

    @BeforeAll
    static void migrateV17RowsToV18() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUsername(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        jdbcTemplate = new JdbcTemplate(dataSource);

        migrateToVersion(dataSource, "17");
        jdbcTemplate.update("insert into auth.users (id) values (?)", MEMBER);
        // 카테고리 (sort_order, id) 순서 = 시스템보다 앞선 커스텀 · CAFE(2) · 비활성 커스텀(3) · 동률 커스텀 둘(4, id 순) · SYSTEM_5(5).
        // id 순서(시스템 → 커스텀)·몫을 넣는 순서와 모두 다르다.
        tieSmallerId = insertCustomCategory("동률-앞", 4, true);
        inactiveCustom = insertCustomCategory("삭제됨", 3, false);
        tieLargerId = insertCustomCategory("동률-뒤", 4, true);
        customFirst = insertCustomCategory("맨앞", 0, true);

        septemberId = insertBudget("2026-09-01");
        insertCategoryShare(septemberId, SYSTEM_5, "500.00");
        insertCategoryShare(septemberId, tieLargerId, "40.50");
        insertCategoryShare(septemberId, inactiveCustom, "30.00");
        insertCategoryShare(septemberId, CAFE, "20.00");
        insertCategoryShare(septemberId, tieSmallerId, "0.00");
        insertCategoryShare(septemberId, customFirst, "10.00");
        octoberId = insertBudget("2026-10-01");
        insertCategoryShare(octoberId, SYSTEM_7, "70.00");
        insertCategoryShare(octoberId, FOOD, "100.00");

        migrateToVersion(dataSource, "18");
    }

    @Test
    @DisplayName("V18 은 예산마다 카테고리 (sort_order, id) 순으로 줄 순서를 0부터 채우고, 컬럼은 not null 이며 금액은 그대로다")
    void v18_fills_line_order_from_category_sort_order() {
        assertThat(lines(septemberId))
                .containsExactly(
                        "0:" + customFirst + "=10.00",
                        "1:" + CAFE + "=20.00",
                        "2:" + inactiveCustom + "=30.00",
                        "3:" + tieSmallerId + "=0.00",
                        "4:" + tieLargerId + "=40.50",
                        "5:" + SYSTEM_5 + "=500.00");
        assertThat(lines(octoberId)).containsExactly("0:" + FOOD + "=100.00", "1:" + SYSTEM_7 + "=70.00");
        assertThat(jdbcTemplate.queryForObject(
                        """
                        select is_nullable from information_schema.columns
                        where table_schema = 'public' and table_name = 'budget_category_allocation'
                          and column_name = 'sort_order'
                        """,
                        String.class))
                .isEqualTo("NO");
    }

    private static void migrateToVersion(DataSource dataSource, String version) {
        Flyway.configure()
                .dataSource(dataSource)
                .locations(MIGRATION_LOCATION)
                .target(MigrationVersion.fromVersion(version))
                .load()
                .migrate();
    }

    private static long insertCustomCategory(String name, int sortOrder, boolean active) {
        Long id = jdbcTemplate.queryForObject(
                """
                insert into category (transaction_type, code, display_name_ko, display_name_en, sort_order,
                                      owner_member_id, is_active)
                values ('EXPENSE', 'CUSTOM', ?, ?, ?, ?, ?) returning id
                """,
                Long.class,
                name,
                name,
                sortOrder,
                MEMBER,
                active);
        return id == null ? 0 : id;
    }

    private static long insertBudget(String month) {
        Long id = jdbcTemplate.queryForObject(
                "insert into budget (member_id, month, currency_code, total_amount)"
                        + " values (?, cast(? as date), 'KRW', 1000.00) returning id",
                Long.class,
                MEMBER,
                month);
        return id == null ? 0 : id;
    }

    private static void insertCategoryShare(long budgetId, long categoryId, String amount) {
        jdbcTemplate.update(
                "insert into budget_category_allocation (budget_id, category_id, amount) values (?, ?, ?::numeric)",
                budgetId,
                categoryId,
                amount);
    }

    /** 예산의 몫을 줄 순서대로 "줄순서:카테고리id=금액" 으로 편다. */
    private static List<String> lines(long budgetId) {
        return jdbcTemplate.queryForList(
                """
                select sort_order || ':' || category_id || '=' || amount from budget_category_allocation
                where budget_id = ? order by sort_order
                """,
                String.class,
                budgetId);
    }
}

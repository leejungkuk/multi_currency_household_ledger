package com.self.multi_currency_household_ledger.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** V16 이 실패하는 경로라 자기 컨테이너를 쓴다 — 다른 마이그레이션 테스트의 DB 상태를 흔들지 않는다. */
@Testcontainers
class BudgetPaymentGroupTotalMigrationTest {

    private static final String MIGRATION_LOCATION = "classpath:db/migration";
    private static final UUID MEMBER = UUID.fromString("00000000-0000-0000-0000-000000000701");

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine").withInitScript("testcontainers/auth-users-stub.sql");

    @Test
    @DisplayName("V15 에 알 수 없는 결제수단 그룹 몫이나 몫 합 > 전체 행이 있으면 V16 이 V15 그대로 실패하고, 고친 뒤 다시 올리면 성공한다")
    void v16_rejects_payment_group_shares_over_total_until_fixed() {
        DataSource dataSource = dataSource();
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        migrateToVersion(dataSource, "15");
        jdbcTemplate.update("insert into auth.users (id) values (?)", MEMBER);
        Long budgetId = jdbcTemplate.queryForObject(
                "insert into budget (member_id, month, currency_code, total_amount)"
                        + " values (?, date '2026-09-01', 'KRW', 100.00) returning id",
                Long.class,
                MEMBER);
        // v1 은 몫 합 > 전체를 저장했고, V14 는 payment_group 값을 제한하지 않았다 — 개발 DB 에 남아 있을 수 있는 모양이다.
        jdbcTemplate.update(
                "insert into budget_allocation (budget_id, payment_group, amount)"
                        + " values (?, 'CREDIT_CARD', 60.00), (?, 'CASH_AND_DEBIT', 41.00), (?, 'MOBILE_WALLET', 5.00)",
                budgetId,
                budgetId,
                budgetId);

        assertThatThrownBy(() -> migrateToVersion(dataSource, "16"))
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("ck_budget_allocation_known_group");
        assertStillV15(jdbcTemplate, budgetId, 3);

        jdbcTemplate.update(
                "delete from budget_allocation where budget_id = ? and payment_group = 'MOBILE_WALLET'", budgetId);
        assertThatThrownBy(() -> migrateToVersion(dataSource, "16"))
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("ck_budget_payment_groups_within_total");
        assertStillV15(jdbcTemplate, budgetId, 2);

        jdbcTemplate.update(
                "update budget_allocation set amount = 40.00 where budget_id = ? and payment_group = 'CASH_AND_DEBIT'",
                budgetId);
        migrateToVersion(dataSource, "16");

        assertThat(jdbcTemplate.queryForObject(
                        "select credit_card_amount || '/' || cash_and_debit_amount from budget where id = ?",
                        String.class,
                        budgetId))
                .isEqualTo("60.00/40.00");
    }

    /** 실패한 V16 은 통째로 롤백된다 — 옛 테이블과 그 행이 남고, 새 컬럼·테이블은 없다. */
    private void assertStillV15(JdbcTemplate jdbcTemplate, Long budgetId, int allocationRows) {
        assertThat(jdbcTemplate.queryForObject(
                        "select count(*) from budget_allocation where budget_id = ?", Integer.class, budgetId))
                .isEqualTo(allocationRows);
        assertThat(jdbcTemplate.queryForObject(
                        "select count(*) from information_schema.columns"
                                + " where table_name = 'budget' and column_name = 'credit_card_amount'",
                        Integer.class))
                .isZero();
        assertThat(jdbcTemplate.queryForObject(
                        "select to_regclass('budget_category_allocation') is null", Boolean.class))
                .isTrue();
    }

    private DataSource dataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUsername(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        return dataSource;
    }

    private void migrateToVersion(DataSource dataSource, String version) {
        Flyway.configure()
                .dataSource(dataSource)
                .locations(MIGRATION_LOCATION)
                .target(MigrationVersion.fromVersion(version))
                .load()
                .migrate();
    }
}

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

@Testcontainers
class LedgerEntryChecksMigrationTest {

    private static final String MIGRATION_LOCATION = "classpath:db/migration";

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine").withInitScript("testcontainers/auth-users-stub.sql");

    @Test
    @DisplayName("V17은 금액 0 거래가 있으면 실패하고, 그 행을 고친 뒤에는 올라간다")
    void v17_rejects_existing_violation_until_fixed() {
        DataSource dataSource = dataSource();
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        UUID memberId = UUID.fromString("00000000-0000-0000-0000-000000000501");

        migrateToVersion(dataSource, "16");
        jdbcTemplate.update("insert into auth.users (id) values (?)", memberId);
        insertZeroAmountLedgerEntry(jdbcTemplate, memberId);

        // Flyway 메시지에는 V17 문장 전체(제약 이름 4개)가 실린다 — Postgres 오류의 따옴표 붙은 이름으로 어떤 제약이 걸렸는지 단언한다.
        assertThatThrownBy(() -> migrateToVersion(dataSource, "17"))
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("check constraint \"ck_ledger_entry_original_amount\"");

        jdbcTemplate.update("update ledger_entry set original_amount = 1.00 where member_id = ?", memberId);
        migrateToVersion(dataSource, "17");

        assertThat(count(
                        jdbcTemplate,
                        "select count(*) from pg_constraint where conname = 'ck_ledger_entry_original_amount'"))
                .isEqualTo(1);
        assertThat(count(jdbcTemplate, "select count(*) from ledger_entry where member_id = ?", memberId))
                .isEqualTo(1);
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

    private void insertZeroAmountLedgerEntry(JdbcTemplate jdbcTemplate, UUID memberId) {
        jdbcTemplate.update(
                """
                insert into ledger_entry (
                    member_id,
                    transaction_type,
                    category_id,
                    asset_id,
                    original_amount,
                    currency_code,
                    applied_rate,
                    rate_base_date,
                    krw_amount,
                    transaction_date,
                    memo,
                    created_at,
                    updated_at
                )
                values (?, 'EXPENSE', 1, 1, 0.00, 'KRW', 1.000000, '2026-10-01',
                        0.00, '2026-10-01', 'V17 violation', now(), now())
                """,
                memberId);
    }

    private int count(JdbcTemplate jdbcTemplate, String sql, Object... args) {
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return count == null ? 0 : count;
    }
}

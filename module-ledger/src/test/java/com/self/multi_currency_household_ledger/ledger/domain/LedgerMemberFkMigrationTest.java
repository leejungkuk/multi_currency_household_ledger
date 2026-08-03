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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class LedgerMemberFkMigrationTest {

    private static final String MIGRATION_LOCATION = "classpath:db/migration";

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine").withInitScript("testcontainers/auth-users-stub.sql");

    @Test
    @DisplayName("V10은 orphan을 거부하고 auth.users 삭제 시 회원 거래를 연쇄 삭제한다")
    void v10_enforces_auth_user_foreign_key_with_delete_cascade() {
        DataSource dataSource = dataSource();
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        UUID orphanMemberId = UUID.fromString("00000000-0000-0000-0000-000000000301");
        UUID memberId = UUID.fromString("00000000-0000-0000-0000-000000000302");
        UUID missingMemberId = UUID.fromString("00000000-0000-0000-0000-000000000303");

        migrateToVersion(dataSource, "9");
        insertLedgerEntry(jdbcTemplate, orphanMemberId);

        assertThatThrownBy(() -> migrateToVersion(dataSource, "10"))
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("fk_ledger_entry_member");

        jdbcTemplate.update("delete from ledger_entry where member_id = ?", orphanMemberId);
        migrateToVersion(dataSource, "10");

        jdbcTemplate.update("insert into auth.users (id) values (?)", memberId);
        insertLedgerEntry(jdbcTemplate, memberId);
        jdbcTemplate.update("delete from auth.users where id = ?", memberId);

        assertThat(ledgerEntryCount(jdbcTemplate, memberId)).isZero();
        assertThatThrownBy(() -> insertLedgerEntry(jdbcTemplate, missingMemberId))
                .isInstanceOf(DataIntegrityViolationException.class);
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

    private void insertLedgerEntry(JdbcTemplate jdbcTemplate, UUID memberId) {
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
                values (?, 'EXPENSE', 1, 1, 1000.00, 'KRW', 1.000000, '2026-08-03',
                        1000.00, '2026-08-03', 'member FK migration', now(), now())
                """,
                memberId);
    }

    private int ledgerEntryCount(JdbcTemplate jdbcTemplate, UUID memberId) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from ledger_entry where member_id = ?", Integer.class, memberId);
        return count == null ? 0 : count;
    }
}

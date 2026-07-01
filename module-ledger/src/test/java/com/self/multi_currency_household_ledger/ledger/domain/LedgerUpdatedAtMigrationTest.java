package com.self.multi_currency_household_ledger.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class LedgerUpdatedAtMigrationTest {

    private static final String MIGRATION_LOCATION = "classpath:db/migration";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    @DisplayName("V8은 legacy NULL updated_at을 백필하고 NOT NULL과 델타 pull 인덱스를 적용한다")
    void v8_backfills_legacy_null_updated_at_before_enforcing_not_null() {
        DataSource dataSource = dataSource();
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        UUID memberId = UUID.fromString("00000000-0000-0000-0000-000000000201");
        LocalDateTime legacyCreatedAt = LocalDateTime.of(2026, 6, 1, 12, 34, 56, 123456000);

        migrateToVersion(dataSource, "7");
        insertLegacyLedgerEntry(jdbcTemplate, memberId, legacyCreatedAt);
        assertThat(isNullable(jdbcTemplate, "ledger_entry", "updated_at")).isEqualTo("YES");

        migrateToVersion(dataSource, "8");

        LocalDateTime backfilledUpdatedAt = jdbcTemplate.queryForObject(
                """
                select updated_at
                from ledger_entry
                where member_id = cast(? as uuid)
                """,
                LocalDateTime.class,
                memberId.toString());
        assertThat(backfilledUpdatedAt).isEqualTo(legacyCreatedAt);
        assertThat(isNullable(jdbcTemplate, "ledger_entry", "updated_at")).isEqualTo("NO");
        assertThat(indexDefinition(jdbcTemplate, "idx_ledger_member_updated_at_id")
                        .toLowerCase(Locale.ROOT))
                .contains("create index idx_ledger_member_updated_at_id")
                .contains("(member_id, updated_at, id)");
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

    private void insertLegacyLedgerEntry(JdbcTemplate jdbcTemplate, UUID memberId, LocalDateTime createdAt) {
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
                values (
                    cast(? as uuid),
                    'EXPENSE',
                    1,
                    1,
                    1000.00,
                    'KRW',
                    1.000000,
                    '2026-06-01',
                    1000.00,
                    '2026-06-01',
                    'legacy null updated_at',
                    ?,
                    null
                )
                """,
                memberId.toString(),
                Timestamp.valueOf(createdAt));
    }

    private String isNullable(JdbcTemplate jdbcTemplate, String tableName, String columnName) {
        return jdbcTemplate.queryForObject(
                """
                select is_nullable
                from information_schema.columns
                where table_schema = 'public'
                  and table_name = ?
                  and column_name = ?
                """,
                String.class,
                tableName,
                columnName);
    }

    private String indexDefinition(JdbcTemplate jdbcTemplate, String indexName) {
        return jdbcTemplate.queryForObject(
                """
                select indexdef
                from pg_indexes
                where schemaname = 'public'
                  and tablename = 'ledger_entry'
                  and indexname = ?
                """,
                String.class,
                indexName);
    }
}

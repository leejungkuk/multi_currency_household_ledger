package com.self.multi_currency_household_ledger;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

public final class AuthUserFixture {

    private final JdbcTemplate jdbcTemplate;

    public AuthUserFixture(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void reset(UUID... memberIds) {
        jdbcTemplate.update("delete from ledger_entry");
        jdbcTemplate.update("delete from auth.users");
        for (UUID memberId : memberIds) {
            jdbcTemplate.update("insert into auth.users (id) values (?)", memberId);
        }
    }

    /**
     * 시각·익명 플래그를 지정해 {@code auth.users} 행을 넣는다. 버려진 익명 계정 정리 배치의 후보 판정을
     * 검증하는 테스트 전용이다.
     *
     * <p>{@code lastSignInAt} 은 null 을 허용한다 — 익명 계정은 재로그인이 없어 실제로 NULL 로 남는다.
     */
    public void insertUser(
            UUID memberId, boolean anonymous, Instant createdAt, Instant updatedAt, Instant lastSignInAt) {
        jdbcTemplate.update(
                """
                insert into auth.users (id, is_anonymous, created_at, updated_at, last_sign_in_at)
                values (?, ?, ?, ?, ?)
                """,
                ps -> {
                    ps.setObject(1, memberId);
                    ps.setBoolean(2, anonymous);
                    ps.setObject(3, utc(createdAt));
                    ps.setObject(4, utc(updatedAt));
                    ps.setObject(5, utc(lastSignInAt));
                });
    }

    /** {@code auth.sessions} 행을 넣는다. {@code updatedAt} 은 refresh 이력이 없는 세션을 재현하려고 null 을 허용한다. */
    public void insertSession(UUID memberId, Instant createdAt, Instant updatedAt) {
        jdbcTemplate.update(
                """
                insert into auth.sessions (id, user_id, created_at, updated_at) values (?, ?, ?, ?)
                """,
                ps -> {
                    ps.setObject(1, UUID.randomUUID());
                    ps.setObject(2, memberId);
                    ps.setObject(3, utc(createdAt));
                    ps.setObject(4, utc(updatedAt));
                });
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}

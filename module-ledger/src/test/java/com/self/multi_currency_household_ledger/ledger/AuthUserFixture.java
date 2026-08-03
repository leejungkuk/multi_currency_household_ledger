package com.self.multi_currency_household_ledger.ledger;

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
}

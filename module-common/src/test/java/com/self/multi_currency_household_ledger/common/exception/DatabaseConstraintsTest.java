package com.self.multi_currency_household_ledger.common.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class DatabaseConstraintsTest {

    @Test
    @DisplayName("cause 사슬의 회원 FK 제약명만 식별한다")
    void identifies_only_ledger_member_foreign_key_in_cause_chain() {
        assertThat(DatabaseConstraints.isLedgerEntryMemberForeignKeyViolation(
                        constraintViolation("fk_ledger_entry_member")))
                .isTrue();
        assertThat(DatabaseConstraints.isLedgerEntryMemberForeignKeyViolation(
                        constraintViolation("uq_ledger_entry_member_client_entry")))
                .isFalse();
        assertThat(DatabaseConstraints.isLedgerEntryMemberForeignKeyViolation(
                        new DataIntegrityViolationException("unknown")))
                .isFalse();
    }

    @Test
    @DisplayName("카테고리 owner FK는 별도 검사만 식별하고 기존 ledger FK 검사는 false를 유지한다")
    void identifies_category_owner_foreign_key_without_widening_ledger_check() {
        DataIntegrityViolationException exception = constraintViolation("fk_category_owner_member");

        assertThat(DatabaseConstraints.isCategoryOwnerMemberForeignKeyViolation(exception))
                .isTrue();
        assertThat(DatabaseConstraints.isLedgerEntryMemberForeignKeyViolation(exception))
                .isFalse();
    }

    @Test
    @DisplayName("cause 사슬이 순환해도 순회가 끝난다")
    void terminates_on_cyclic_cause_chain() {
        SQLException first = new SQLException("first");
        SQLException second = new SQLException("second");
        first.initCause(second);
        second.initCause(first);

        assertThat(DatabaseConstraints.isLedgerEntryMemberForeignKeyViolation(
                        new DataIntegrityViolationException("cyclic", first)))
                .isFalse();
    }

    private static DataIntegrityViolationException constraintViolation(String constraintName) {
        var cause = new org.hibernate.exception.ConstraintViolationException(
                "constraint violation", new SQLException("constraint violation"), "insert", constraintName);
        return new DataIntegrityViolationException("data integrity violation", cause);
    }
}

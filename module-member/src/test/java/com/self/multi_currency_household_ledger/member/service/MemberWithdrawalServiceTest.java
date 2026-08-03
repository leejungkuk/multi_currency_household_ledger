package com.self.multi_currency_household_ledger.member.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import com.self.multi_currency_household_ledger.member.repository.AuthUserRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MemberWithdrawalServiceTest {

    private static final UUID MEMBER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Mock
    private AuthUserRepository authUserRepository;

    private SimpleMeterRegistry meterRegistry;
    private MemberWithdrawalService memberWithdrawalService;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        memberWithdrawalService = new MemberWithdrawalService(authUserRepository, meterRegistry);
    }

    @Test
    @DisplayName("auth.users 한 행을 삭제하면 deleted 카운터를 증가시킨다")
    void withdraw_increments_deleted_counter_when_row_is_deleted() {
        given(authUserRepository.deleteById(MEMBER_ID)).willReturn(1);

        memberWithdrawalService.withdraw(MEMBER_ID);

        then(authUserRepository).should().deleteById(MEMBER_ID);
        assertThat(counter("deleted")).isEqualTo(1.0);
        assertThat(counter("noop")).isZero();
    }

    @Test
    @DisplayName("이미 삭제된 회원이면 예외 없이 noop 카운터를 증가시킨다")
    void withdraw_is_idempotent_and_increments_noop_counter_when_no_row_is_deleted() {
        given(authUserRepository.deleteById(MEMBER_ID)).willReturn(0);

        memberWithdrawalService.withdraw(MEMBER_ID);

        then(authUserRepository).should().deleteById(MEMBER_ID);
        assertThat(counter("deleted")).isZero();
        assertThat(counter("noop")).isEqualTo(1.0);
    }

    private double counter(String result) {
        var counter = meterRegistry
                .find("woni.member.withdrawal")
                .tag("result", result)
                .counter();
        return counter == null ? 0.0 : counter.count();
    }
}

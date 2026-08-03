package com.self.multi_currency_household_ledger.member.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import com.self.multi_currency_household_ledger.member.client.AppleTokenRevoker;
import com.self.multi_currency_household_ledger.member.client.RevokeOutcome;
import com.self.multi_currency_household_ledger.member.repository.AuthUserRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

@ExtendWith(MockitoExtension.class)
class MemberWithdrawalServiceTest {

    private static final UUID MEMBER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Mock
    private AuthUserRepository authUserRepository;

    @Mock
    private AppleTokenRevoker appleTokenRevoker;

    @Mock
    private PlatformTransactionManager transactionManager;

    @Mock
    private TransactionStatus transactionStatus;

    private SimpleMeterRegistry meterRegistry;
    private MemberWithdrawalService memberWithdrawalService;

    @BeforeEach
    void setUp() {
        given(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .willReturn(transactionStatus);
        meterRegistry = new SimpleMeterRegistry();
        memberWithdrawalService =
                new MemberWithdrawalService(authUserRepository, meterRegistry, appleTokenRevoker, transactionManager);
    }

    @Test
    @DisplayName("삭제와 Apple revoke가 성공하면 서로 분리된 카운터를 증가시킨다")
    void withdraw_increments_deleted_counter_when_row_is_deleted() {
        given(authUserRepository.deleteById(MEMBER_ID)).willReturn(1);
        given(appleTokenRevoker.revoke("authorization-code")).willReturn(RevokeOutcome.REVOKED);

        memberWithdrawalService.withdraw(MEMBER_ID, "authorization-code");

        then(authUserRepository).should().deleteById(MEMBER_ID);
        then(appleTokenRevoker).should().revoke("authorization-code");
        assertThat(counter("deleted")).isEqualTo(1.0);
        assertThat(counter("noop")).isZero();
        assertThat(appleRevokeCounter("revoked")).isEqualTo(1.0);
        assertThat(appleRevokeCounter("skipped")).isZero();
        assertThat(appleRevokeCounter("failed")).isZero();
    }

    @Test
    @DisplayName("이미 삭제된 회원과 코드 없음은 noop과 skipped 카운터를 증가시킨다")
    void withdraw_is_idempotent_and_increments_noop_counter_when_no_row_is_deleted() {
        given(authUserRepository.deleteById(MEMBER_ID)).willReturn(0);
        given(appleTokenRevoker.revoke(null)).willReturn(RevokeOutcome.SKIPPED);

        memberWithdrawalService.withdraw(MEMBER_ID, null);

        then(authUserRepository).should().deleteById(MEMBER_ID);
        then(appleTokenRevoker).should().revoke(null);
        assertThat(counter("deleted")).isZero();
        assertThat(counter("noop")).isEqualTo(1.0);
        assertThat(appleRevokeCounter("skipped")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Apple revoke 실패는 예외로 바꾸지 않고 failed 카운터만 증가시킨다")
    void withdraw_records_failed_revoke_without_throwing() {
        given(authUserRepository.deleteById(MEMBER_ID)).willReturn(1);
        given(appleTokenRevoker.revoke("failed-code")).willReturn(RevokeOutcome.FAILED);

        assertThatCode(() -> memberWithdrawalService.withdraw(MEMBER_ID, "failed-code"))
                .doesNotThrowAnyException();

        assertThat(counter("deleted")).isEqualTo(1.0);
        assertThat(appleRevokeCounter("failed")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("삭제가 실패하면 예외를 전파하고 Apple revoke를 호출하지 않는다")
    void withdraw_does_not_revoke_when_delete_fails() {
        IllegalStateException failure = new IllegalStateException("delete failed");
        given(authUserRepository.deleteById(MEMBER_ID)).willThrow(failure);

        assertThatThrownBy(() -> memberWithdrawalService.withdraw(MEMBER_ID, "authorization-code"))
                .isSameAs(failure);

        then(appleTokenRevoker).shouldHaveNoInteractions();
        assertThat(counter("deleted")).isZero();
        assertThat(counter("noop")).isZero();
        assertThat(appleRevokeCounter("revoked")).isZero();
        assertThat(appleRevokeCounter("skipped")).isZero();
        assertThat(appleRevokeCounter("failed")).isZero();
    }

    private double counter(String result) {
        var counter = meterRegistry
                .find("woni.member.withdrawal")
                .tag("result", result)
                .counter();
        return counter == null ? 0.0 : counter.count();
    }

    private double appleRevokeCounter(String result) {
        var counter = meterRegistry
                .find("woni.member.apple_revoke")
                .tag("result", result)
                .counter();
        return counter == null ? 0.0 : counter.count();
    }
}

package com.self.multi_currency_household_ledger.member.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.then;

import com.self.multi_currency_household_ledger.common.dto.ApiResponse;
import com.self.multi_currency_household_ledger.member.dto.MemberWithdrawalRequest;
import com.self.multi_currency_household_ledger.member.service.MemberWithdrawalService;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MemberControllerTest {

    private static final UUID MEMBER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Mock
    private MemberWithdrawalService memberWithdrawalService;

    @InjectMocks
    private MemberController memberController;

    @Test
    @DisplayName("본문이 없으면 JWT subject 회원과 null 코드를 서비스에 전달한다")
    void withdraw_without_body_deletes_current_member_and_returns_success() {
        ApiResponse<Void> response = memberController.withdraw(MEMBER_ID, null);

        assertThat(response.success()).isTrue();
        assertThat(response.data()).isNull();
        then(memberWithdrawalService).should().withdraw(MEMBER_ID, null);
    }

    @Test
    @DisplayName("본문이 있으면 Apple authorization code를 가공하지 않고 서비스에 전달한다")
    void withdraw_with_body_passes_apple_authorization_code() {
        ApiResponse<Void> response =
                memberController.withdraw(MEMBER_ID, new MemberWithdrawalRequest("authorization-code"));

        assertThat(response.success()).isTrue();
        assertThat(response.data()).isNull();
        then(memberWithdrawalService).should().withdraw(MEMBER_ID, "authorization-code");
    }
}

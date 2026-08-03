package com.self.multi_currency_household_ledger.member.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.then;

import com.self.multi_currency_household_ledger.common.dto.ApiResponse;
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
    @DisplayName("JWT subject 회원을 탈퇴시키고 성공 봉투를 반환한다")
    void withdraw_deletes_current_member_and_returns_success() {
        ApiResponse<Void> response = memberController.withdraw(MEMBER_ID);

        assertThat(response.success()).isTrue();
        assertThat(response.data()).isNull();
        then(memberWithdrawalService).should().withdraw(MEMBER_ID);
    }
}

package com.self.multi_currency_household_ledger.member.controller;

import com.self.multi_currency_household_ledger.common.annotation.CurrentMemberId;
import com.self.multi_currency_household_ledger.common.dto.ApiResponse;
import com.self.multi_currency_household_ledger.member.service.MemberWithdrawalService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/members")
public class MemberController {

    private final MemberWithdrawalService memberWithdrawalService;

    @DeleteMapping("/me")
    public ApiResponse<Void> withdraw(@CurrentMemberId UUID memberId) {
        memberWithdrawalService.withdraw(memberId);
        return ApiResponse.success(null);
    }
}

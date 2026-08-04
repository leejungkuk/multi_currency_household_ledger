package com.self.multi_currency_household_ledger.member.controller;

import com.self.multi_currency_household_ledger.common.annotation.CurrentMemberId;
import com.self.multi_currency_household_ledger.common.dto.ApiResponse;
import com.self.multi_currency_household_ledger.member.dto.MemberWithdrawalRequest;
import com.self.multi_currency_household_ledger.member.service.MemberWithdrawalService;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/members")
public class MemberController {

    private final MemberWithdrawalService memberWithdrawalService;

    @DeleteMapping("/me")
    public ApiResponse<Void> withdraw(
            @CurrentMemberId UUID memberId, @Valid @RequestBody(required = false) MemberWithdrawalRequest request) {
        memberWithdrawalService.withdraw(memberId, request == null ? null : request.appleAuthorizationCode());
        return ApiResponse.success(null);
    }
}

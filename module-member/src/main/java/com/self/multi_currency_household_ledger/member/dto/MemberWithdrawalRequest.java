package com.self.multi_currency_household_ledger.member.dto;

import jakarta.validation.constraints.Size;

public record MemberWithdrawalRequest(@Size(max = 512) String appleAuthorizationCode) {}

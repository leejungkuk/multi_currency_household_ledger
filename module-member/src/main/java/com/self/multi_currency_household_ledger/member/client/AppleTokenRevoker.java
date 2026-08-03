package com.self.multi_currency_household_ledger.member.client;

public interface AppleTokenRevoker {

    RevokeOutcome revoke(String authorizationCode);
}

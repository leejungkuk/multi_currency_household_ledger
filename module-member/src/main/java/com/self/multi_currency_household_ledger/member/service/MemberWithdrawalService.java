package com.self.multi_currency_household_ledger.member.service;

import com.self.multi_currency_household_ledger.member.client.AppleTokenRevoker;
import com.self.multi_currency_household_ledger.member.client.RevokeOutcome;
import com.self.multi_currency_household_ledger.member.repository.AuthUserRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class MemberWithdrawalService {

    private final AuthUserRepository authUserRepository;
    private final MeterRegistry meterRegistry;
    private final AppleTokenRevoker appleTokenRevoker;
    private final TransactionTemplate transactionTemplate;

    public MemberWithdrawalService(
            AuthUserRepository authUserRepository,
            MeterRegistry meterRegistry,
            AppleTokenRevoker appleTokenRevoker,
            PlatformTransactionManager transactionManager) {
        this.authUserRepository = authUserRepository;
        this.meterRegistry = meterRegistry;
        this.appleTokenRevoker = appleTokenRevoker;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public void withdraw(UUID memberId, String appleAuthorizationCode) {
        int deletedRows = transactionTemplate.execute(status -> authUserRepository.deleteById(memberId));
        String result = deletedRows > 0 ? "deleted" : "noop";
        Counter.builder("woni.member.withdrawal")
                .tag("result", result)
                .register(meterRegistry)
                .increment();

        RevokeOutcome outcome = appleTokenRevoker.revoke(appleAuthorizationCode);
        String revokeResult =
                switch (outcome) {
                    case REVOKED -> "revoked";
                    case SKIPPED -> "skipped";
                    case FAILED -> "failed";
                };
        Counter.builder("woni.member.apple_revoke")
                .tag("result", revokeResult)
                .register(meterRegistry)
                .increment();
    }
}

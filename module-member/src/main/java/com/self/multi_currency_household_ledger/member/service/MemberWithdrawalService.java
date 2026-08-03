package com.self.multi_currency_household_ledger.member.service;

import com.self.multi_currency_household_ledger.member.repository.AuthUserRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MemberWithdrawalService {

    private final AuthUserRepository authUserRepository;
    private final MeterRegistry meterRegistry;

    @Transactional
    public void withdraw(UUID memberId) {
        int deletedRows = authUserRepository.deleteById(memberId);
        String result = deletedRows > 0 ? "deleted" : "noop";
        Counter.builder("woni.member.withdrawal")
                .tag("result", result)
                .register(meterRegistry)
                .increment();
    }
}

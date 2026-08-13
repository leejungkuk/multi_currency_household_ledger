package com.self.multi_currency_household_ledger.ledger.service;

import com.self.multi_currency_household_ledger.ledger.domain.LedgerEntryRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 호출 시점까지 커밋된 회원 거래를 지운다. 삭제 문 이후 도착한 create/sync는 새 데이터로 남으며, purge는 이후 쓰기를
 * 봉인하지 않는다.
 */
@Service
public class LedgerPurgeService {

    private final LedgerEntryRepository ledgerEntryRepository;
    private final MeterRegistry meterRegistry;
    private final TransactionTemplate transactionTemplate;

    public LedgerPurgeService(
            LedgerEntryRepository ledgerEntryRepository,
            MeterRegistry meterRegistry,
            PlatformTransactionManager transactionManager) {
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.meterRegistry = meterRegistry;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public void purge(UUID memberId) {
        int deletedRows = transactionTemplate.execute(status -> ledgerEntryRepository.deleteAllByMemberId(memberId));
        String result = deletedRows > 0 ? "deleted" : "noop";
        Counter.builder("woni.ledger.purge")
                .tag("result", result)
                .register(meterRegistry)
                .increment();
    }
}

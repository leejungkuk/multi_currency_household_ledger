package com.self.multi_currency_household_ledger.ledger.service;

import com.self.multi_currency_household_ledger.ledger.domain.CategoryRepository;
import com.self.multi_currency_household_ledger.ledger.domain.LedgerEntryRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 호출 시점까지 커밋된 회원 거래와 그 회원의 커스텀 카테고리를 지운다. 삭제 문 이후 도착한 create/sync는 새 데이터로 남으며,
 * purge는 이후 쓰기를 봉인하지 않는다.
 */
@Service
public class LedgerPurgeService {

    private final LedgerEntryRepository ledgerEntryRepository;
    private final CategoryRepository categoryRepository;
    private final MeterRegistry meterRegistry;
    private final TransactionTemplate transactionTemplate;

    public LedgerPurgeService(
            LedgerEntryRepository ledgerEntryRepository,
            CategoryRepository categoryRepository,
            MeterRegistry meterRegistry,
            PlatformTransactionManager transactionManager) {
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.categoryRepository = categoryRepository;
        this.meterRegistry = meterRegistry;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public void purge(UUID memberId) {
        int deletedRows = transactionTemplate.execute(status -> {
            // fk_ledger_category 에 cascade 가 없어 참조 거래가 남아 있으면 카테고리 삭제가 FK 위반이다 — 거래를 먼저 지운다.
            int deletedEntries = ledgerEntryRepository.deleteAllByMemberId(memberId);
            int deletedCategories = categoryRepository.deleteAllByOwnerMemberId(memberId);
            return deletedEntries + deletedCategories;
        });
        String result = deletedRows > 0 ? "deleted" : "noop";
        Counter.builder("woni.ledger.purge")
                .tag("result", result)
                .register(meterRegistry)
                .increment();
    }
}

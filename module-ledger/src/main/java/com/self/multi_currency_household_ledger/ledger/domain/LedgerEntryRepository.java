package com.self.multi_currency_household_ledger.ledger.domain;

import com.self.multi_currency_household_ledger.exchange.domain.CurrencyCode;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {

    List<LedgerEntry> findByCurrencyCodeNotAndTransactionDateGreaterThanEqualOrderByTransactionDateAscIdAsc(
            CurrencyCode currencyCode, LocalDate transactionDate);

    default List<LedgerEntry> findForeignEntriesOnOrAfter(LocalDate transactionDate) {
        return findByCurrencyCodeNotAndTransactionDateGreaterThanEqualOrderByTransactionDateAscIdAsc(
                CurrencyCode.KRW, transactionDate);
    }
}

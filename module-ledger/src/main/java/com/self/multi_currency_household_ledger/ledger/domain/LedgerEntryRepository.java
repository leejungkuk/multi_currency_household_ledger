package com.self.multi_currency_household_ledger.ledger.domain;

import com.self.multi_currency_household_ledger.exchange.domain.CurrencyCode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {

    @Query(
            """
            select coalesce(sum(entry.krwAmount), 0)
            from LedgerEntry entry
            where entry.memberId = :memberId
              and entry.transactionType = :transactionType
              and entry.transactionDate >= :startDate
              and entry.transactionDate < :endDate
            """)
    BigDecimal sumKrwAmountByMemberIdAndTransactionTypeAndTransactionDateRange(
            @Param("memberId") UUID memberId,
            @Param("transactionType") TransactionType transactionType,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    List<LedgerEntry>
            findByMemberIdAndTransactionDateGreaterThanEqualAndTransactionDateLessThanOrderByTransactionDateDescIdDesc(
                    UUID memberId, LocalDate startDate, LocalDate endDate, Pageable pageable);

    List<LedgerEntry> findByCurrencyCodeNotAndTransactionDateGreaterThanEqualOrderByTransactionDateAscIdAsc(
            CurrencyCode currencyCode, LocalDate transactionDate);

    default List<LedgerEntry> findForeignEntriesOnOrAfter(LocalDate transactionDate) {
        return findByCurrencyCodeNotAndTransactionDateGreaterThanEqualOrderByTransactionDateAscIdAsc(
                CurrencyCode.KRW, transactionDate);
    }
}

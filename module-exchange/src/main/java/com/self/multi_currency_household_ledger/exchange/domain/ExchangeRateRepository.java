package com.self.multi_currency_household_ledger.exchange.domain;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ExchangeRateRepository extends JpaRepository<ExchangeRate, Long> {

    Optional<ExchangeRate> findByCurrencyCodeAndBaseDate(CurrencyCode currencyCode, LocalDate baseDate);

    Optional<ExchangeRate> findTopByCurrencyCodeOrderByBaseDateDesc(CurrencyCode currencyCode);

    Optional<ExchangeRate> findTopByCurrencyCodeOrderByBaseDateAsc(CurrencyCode currencyCode);

    Optional<ExchangeRate> findTopByCurrencyCodeAndBaseDateLessThanEqualOrderByBaseDateDesc(
            CurrencyCode currencyCode, LocalDate baseDate);

    List<ExchangeRate> findByBaseDate(LocalDate baseDate);

    List<ExchangeRate> findByBaseDateBetweenOrderByBaseDateAscCurrencyCodeAsc(LocalDate from, LocalDate to);

    @Query(
            """
            select rate.baseDate from ExchangeRate rate
            where rate.baseDate between :from and :to
              and rate.currencyCode in :expectedCodes
            group by rate.baseDate
            having count(distinct rate.currencyCode) = :expectedCount
            """)
    List<LocalDate> findCompleteBaseDates(
            @Param("from") LocalDate from,
            @Param("to") LocalDate to,
            @Param("expectedCodes") Collection<CurrencyCode> expectedCodes,
            @Param("expectedCount") long expectedCount);

    @Query(
            """
            select rate
            from ExchangeRate rate
            where rate.baseDate = (
                select max(latest.baseDate)
                from ExchangeRate latest
                where latest.currencyCode = rate.currencyCode
            )
            order by rate.currencyCode asc
            """)
    List<ExchangeRate> findLatestRatesByCurrency();
}

package com.self.multi_currency_household_ledger.exchange.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ExchangeRateRepository extends JpaRepository<ExchangeRate, Long> {

    Optional<ExchangeRate> findByCurrencyCodeAndBaseDate(CurrencyCode currencyCode, LocalDate baseDate);

    Optional<ExchangeRate> findTopByCurrencyCodeOrderByBaseDateDesc(CurrencyCode currencyCode);

    Optional<ExchangeRate> findTopByCurrencyCodeAndBaseDateLessThanEqualOrderByBaseDateDesc(
            CurrencyCode currencyCode, LocalDate baseDate);

    List<ExchangeRate> findByBaseDate(LocalDate baseDate);

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

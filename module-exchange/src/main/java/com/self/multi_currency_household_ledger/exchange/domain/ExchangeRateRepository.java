package com.self.multi_currency_household_ledger.exchange.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExchangeRateRepository extends JpaRepository<ExchangeRate, Long> {

    Optional<ExchangeRate> findByCurrencyCodeAndBaseDate(CurrencyCode currencyCode, LocalDate baseDate);

    Optional<ExchangeRate> findTopByCurrencyCodeOrderByBaseDateDesc(CurrencyCode currencyCode);

    List<ExchangeRate> findByBaseDate(LocalDate baseDate);
}

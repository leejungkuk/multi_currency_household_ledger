package com.self.multi_currency_household_ledger.exchange.domain;

import java.math.BigDecimal;

/** Provider → Service 경계 VO. 인프라가 도메인 엔티티를 직접 생성하지 않도록 분리한다. */
public record FetchedRate(CurrencyCode currencyCode, BigDecimal dealBasRate) {}

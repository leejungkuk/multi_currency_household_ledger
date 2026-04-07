package com.self.multi_currency_household_ledger.exchange.provider;

import java.time.LocalDate;
import java.util.List;

/**
 * 환율 정보 제공자 추상화 (OCP)
 * 새로운 환율 API로 교체 시 이 인터페이스의 구현체만 추가하면 된다.
 */
public interface ExchangeRateProvider {

    List<ExchangeRateApiResponse> getExchangeRates(LocalDate date);
}

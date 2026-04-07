package com.self.multi_currency_household_ledger.exchange.service;

import com.self.multi_currency_household_ledger.common.exception.BusinessException;
import com.self.multi_currency_household_ledger.exchange.domain.CurrencyCode;
import com.self.multi_currency_household_ledger.exchange.domain.ExchangeRate;
import com.self.multi_currency_household_ledger.exchange.domain.ExchangeRateRepository;
import com.self.multi_currency_household_ledger.exchange.provider.ExchangeRateApiResponse;
import com.self.multi_currency_household_ledger.exchange.provider.ExchangeRateProvider;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExchangeRateService {

    private final ExchangeRateRepository exchangeRateRepository;
    private final ExchangeRateProvider exchangeRateProvider;

    /**
     * Provider 호출 → DB 저장 (이미 존재하는 데이터는 건너뜀)
     */
    @Transactional
    public void fetchAndSaveRates(LocalDate date) {
        List<ExchangeRateApiResponse> responses = exchangeRateProvider.getExchangeRates(date);

        for (ExchangeRateApiResponse response : responses) {
            ExchangeRate rate = response.toDomain(date);

            boolean exists = exchangeRateRepository
                    .findByCurrencyCodeAndBaseDate(rate.getCurrencyCode(), date)
                    .isPresent();

            if (!exists) {
                exchangeRateRepository.save(rate);
                log.info("환율 저장 완료: {} {} {}", rate.getCurrencyCode(), rate.getDealBasRate(), date);
            }
        }
    }

    /**
     * 특정 날짜 환율 조회, 없으면 최근 영업일 환율 fallback
     */
    @Transactional(readOnly = true)
    public ExchangeRate getRate(CurrencyCode currencyCode, LocalDate date) {
        return exchangeRateRepository.findByCurrencyCodeAndBaseDate(currencyCode, date)
                .orElseGet(() -> getLatestRate(currencyCode));
    }

    /**
     * 최신 환율 조회
     */
    @Transactional(readOnly = true)
    public ExchangeRate getLatestRate(CurrencyCode currencyCode) {
        return exchangeRateRepository.findTopByCurrencyCodeOrderByBaseDateDesc(currencyCode)
                .orElseThrow(() -> new BusinessException(
                        "EXCHANGE_RATE_NOT_FOUND",
                        currencyCode + " 환율 정보가 존재하지 않습니다."
                ));
    }

    /**
     * 특정 날짜 전체 환율 조회
     */
    @Transactional(readOnly = true)
    public List<ExchangeRate> getAllRatesByDate(LocalDate date) {
        return exchangeRateRepository.findByBaseDate(date);
    }
}

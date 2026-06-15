package com.self.multi_currency_household_ledger.exchange.service;

import com.self.multi_currency_household_ledger.common.exception.BusinessException;
import com.self.multi_currency_household_ledger.exchange.domain.CurrencyCode;
import com.self.multi_currency_household_ledger.exchange.domain.ExchangeRate;
import com.self.multi_currency_household_ledger.exchange.domain.ExchangeRateRepository;
import com.self.multi_currency_household_ledger.exchange.domain.FetchedRate;
import com.self.multi_currency_household_ledger.exchange.exception.ExchangeErrorCode;
import com.self.multi_currency_household_ledger.exchange.provider.ExchangeRateProvider;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExchangeRateService {

    private final ExchangeRateRepository exchangeRateRepository;
    private final ExchangeRateProvider exchangeRateProvider;
    private final Clock clock;

    /**
     * Provider 호출 → DB 저장.
     * 트랜잭션 없이 통화별 saveAndFlush로 독립 커밋한다. unique constraint 위반은 중복 적재이므로 정상 skip.
     */
    public boolean fetchAndSaveRates(LocalDate date) {
        List<FetchedRate> fetched;
        try {
            fetched = exchangeRateProvider.getExchangeRates(date);
        } catch (BusinessException e) {
            log.warn("환율 수집 실패로 저장을 건너뜁니다. date={}, code={}", date, e.getCode());
            return false;
        }

        for (FetchedRate rate : fetched) {
            ExchangeRate entity = ExchangeRate.of(rate.currencyCode(), rate.tts(), date);
            try {
                exchangeRateRepository.saveAndFlush(entity);
                log.info("환율 저장 완료: {} {} {}", entity.getCurrencyCode(), entity.getTts(), date);
            } catch (DataIntegrityViolationException e) {
                log.debug("환율 이미 존재 (skip): {} {}", entity.getCurrencyCode(), date);
            }
        }
        return true;
    }

    @Transactional(readOnly = true)
    public ExchangeRate getRate(CurrencyCode currencyCode, LocalDate date) {
        ExchangeRate.assertNotFuture(date, clock);
        return exchangeRateRepository
                .findByCurrencyCodeAndBaseDate(currencyCode, date)
                .orElseGet(() -> getLatestRate(currencyCode));
    }

    @Transactional(readOnly = true)
    public ExchangeRate getLatestRate(CurrencyCode currencyCode) {
        return exchangeRateRepository
                .findTopByCurrencyCodeOrderByBaseDateDesc(currencyCode)
                .orElseThrow(() -> new BusinessException(ExchangeErrorCode.EXCHANGE_RATE_NOT_FOUND));
    }

    @Transactional(readOnly = true)
    public ExchangeRate getRateOnOrBefore(CurrencyCode currencyCode, LocalDate date) {
        ExchangeRate.assertNotFuture(date, clock);
        return exchangeRateRepository
                .findTopByCurrencyCodeAndBaseDateLessThanEqualOrderByBaseDateDesc(currencyCode, date)
                .orElseThrow(() -> new BusinessException(ExchangeErrorCode.EXCHANGE_RATE_NOT_FOUND));
    }

    @Transactional(readOnly = true)
    public List<ExchangeRate> getAllRatesByDate(LocalDate date) {
        ExchangeRate.assertNotFuture(date, clock);
        return exchangeRateRepository.findByBaseDate(date);
    }

    @Transactional(readOnly = true)
    public List<ExchangeRate> getLatestRatesByCurrency() {
        return exchangeRateRepository.findLatestRatesByCurrency();
    }
}

package com.self.multi_currency_household_ledger.exchange.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.self.multi_currency_household_ledger.common.exception.BusinessException;
import com.self.multi_currency_household_ledger.exchange.domain.CurrencyCode;
import com.self.multi_currency_household_ledger.exchange.domain.ExchangeRate;
import com.self.multi_currency_household_ledger.exchange.domain.ExchangeRateRepository;
import com.self.multi_currency_household_ledger.exchange.domain.FetchedRate;
import com.self.multi_currency_household_ledger.exchange.exception.ExchangeErrorCode;
import com.self.multi_currency_household_ledger.exchange.provider.ExchangeRateProvider;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
class ExchangeRateServiceTest {

    @InjectMocks
    private ExchangeRateService exchangeRateService;

    @Mock
    private ExchangeRateRepository exchangeRateRepository;

    @Mock
    private ExchangeRateProvider exchangeRateProvider;

    private static final LocalDate DATE = LocalDate.of(2026, 4, 3);

    @Nested
    @DisplayName("fetchAndSaveRates()")
    class FetchAndSaveRates {

        @Test
        @DisplayName("Provider 응답을 DB에 저장한다")
        void saves_rates_from_provider() {
            given(exchangeRateProvider.getExchangeRates(DATE))
                    .willReturn(List.of(new FetchedRate(CurrencyCode.USD, new BigDecimal("1300.00"))));

            exchangeRateService.fetchAndSaveRates(DATE);

            verify(exchangeRateRepository).saveAndFlush(any(ExchangeRate.class));
        }

        @Test
        @DisplayName("이미 존재하는 환율은 예외 처리 후 건너뛴다")
        void skips_existing_rates() {
            given(exchangeRateProvider.getExchangeRates(DATE))
                    .willReturn(List.of(new FetchedRate(CurrencyCode.USD, new BigDecimal("1300.00"))));
            given(exchangeRateRepository.saveAndFlush(any(ExchangeRate.class)))
                    .willThrow(DataIntegrityViolationException.class);

            assertThatCode(() -> exchangeRateService.fetchAndSaveRates(DATE)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("일부 통화가 누락되어도 Provider가 반환한 나머지 통화는 저장한다")
        void saves_remaining_rates_when_some_currencies_are_missing() {
            given(exchangeRateProvider.getExchangeRates(DATE))
                    .willReturn(List.of(new FetchedRate(CurrencyCode.EUR, new BigDecimal("1450.00"))));
            ArgumentCaptor<ExchangeRate> captor = ArgumentCaptor.forClass(ExchangeRate.class);

            exchangeRateService.fetchAndSaveRates(DATE);

            verify(exchangeRateRepository).saveAndFlush(captor.capture());
            assertThat(captor.getValue().getCurrencyCode()).isEqualTo(CurrencyCode.EUR);
        }

        @Test
        @DisplayName("Provider 실패는 배경 수집 경로에서 전파하지 않고 저장을 건너뛴다")
        void skips_when_provider_fails() {
            given(exchangeRateProvider.getExchangeRates(DATE))
                    .willThrow(new BusinessException(ExchangeErrorCode.EXCHANGE_API_LIMIT_EXCEEDED));

            assertThatCode(() -> exchangeRateService.fetchAndSaveRates(DATE)).doesNotThrowAnyException();

            verify(exchangeRateRepository, never()).saveAndFlush(any(ExchangeRate.class));
        }
    }

    @Nested
    @DisplayName("getRate()")
    class GetRate {

        @Test
        @DisplayName("해당 날짜 환율이 있으면 반환한다")
        void returns_rate_for_date() {
            var rate = ExchangeRate.of(CurrencyCode.USD, new BigDecimal("1300.00"), DATE);
            given(exchangeRateRepository.findByCurrencyCodeAndBaseDate(CurrencyCode.USD, DATE))
                    .willReturn(Optional.of(rate));

            ExchangeRate result = exchangeRateService.getRate(CurrencyCode.USD, DATE);

            assertThat(result.getTts()).isEqualByComparingTo(new BigDecimal("1300.00"));
        }

        @Test
        @DisplayName("해당 날짜 환율이 없으면 최신 환율로 fallback한다")
        void falls_back_to_latest_rate() {
            var latestRate = ExchangeRate.of(CurrencyCode.USD, new BigDecimal("1290.00"), DATE.minusDays(1));
            given(exchangeRateRepository.findByCurrencyCodeAndBaseDate(CurrencyCode.USD, DATE))
                    .willReturn(Optional.empty());
            given(exchangeRateRepository.findTopByCurrencyCodeOrderByBaseDateDesc(CurrencyCode.USD))
                    .willReturn(Optional.of(latestRate));

            ExchangeRate result = exchangeRateService.getRate(CurrencyCode.USD, DATE);

            assertThat(result.getBaseDate()).isEqualTo(DATE.minusDays(1));
        }

        @Test
        @DisplayName("미래 날짜 조회 시 BusinessException을 던진다")
        void throws_for_future_date() {
            LocalDate future = LocalDate.now(ZoneId.of("Asia/Seoul")).plusDays(1);

            assertThatThrownBy(() -> exchangeRateService.getRate(CurrencyCode.USD, future))
                    .isInstanceOf(BusinessException.class);

            verify(exchangeRateRepository, never()).findByCurrencyCodeAndBaseDate(any(), any());
        }
    }

    @Nested
    @DisplayName("getLatestRate()")
    class GetLatestRate {

        @Test
        @DisplayName("최신 환율을 반환한다")
        void returns_latest_rate() {
            var rate = ExchangeRate.of(CurrencyCode.EUR, new BigDecimal("1450.00"), DATE);
            given(exchangeRateRepository.findTopByCurrencyCodeOrderByBaseDateDesc(CurrencyCode.EUR))
                    .willReturn(Optional.of(rate));

            ExchangeRate result = exchangeRateService.getLatestRate(CurrencyCode.EUR);

            assertThat(result.getCurrencyCode()).isEqualTo(CurrencyCode.EUR);
        }

        @Test
        @DisplayName("환율 데이터가 없으면 BusinessException을 던진다")
        void throws_when_no_rate_exists() {
            given(exchangeRateRepository.findTopByCurrencyCodeOrderByBaseDateDesc(CurrencyCode.GBP))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() -> exchangeRateService.getLatestRate(CurrencyCode.GBP))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(
                            ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo("EXCHANGE_RATE_NOT_FOUND"));
        }
    }

    @Nested
    @DisplayName("getAllRatesByDate()")
    class GetAllRatesByDate {

        @Test
        @DisplayName("특정 날짜 전체 환율을 반환한다")
        void returns_all_rates_for_date() {
            var rates = List.of(
                    ExchangeRate.of(CurrencyCode.USD, new BigDecimal("1300.00"), DATE),
                    ExchangeRate.of(CurrencyCode.EUR, new BigDecimal("1450.00"), DATE));
            given(exchangeRateRepository.findByBaseDate(DATE)).willReturn(rates);

            List<ExchangeRate> result = exchangeRateService.getAllRatesByDate(DATE);

            assertThat(result).hasSize(2);
        }

        @Test
        @DisplayName("미래 날짜 조회 시 BusinessException을 던진다")
        void throws_for_future_date() {
            LocalDate future = LocalDate.now(ZoneId.of("Asia/Seoul")).plusDays(1);

            assertThatThrownBy(() -> exchangeRateService.getAllRatesByDate(future))
                    .isInstanceOf(BusinessException.class);

            verify(exchangeRateRepository, never()).findByBaseDate(any());
        }
    }
}

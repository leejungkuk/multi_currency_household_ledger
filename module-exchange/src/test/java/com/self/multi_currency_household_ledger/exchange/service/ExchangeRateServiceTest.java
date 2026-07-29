package com.self.multi_currency_household_ledger.exchange.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
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
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
class ExchangeRateServiceTest {

    private ExchangeRateService exchangeRateService;

    @Mock
    private ExchangeRateRepository exchangeRateRepository;

    @Mock
    private ExchangeRateProvider exchangeRateProvider;

    private static final LocalDate DATE = LocalDate.of(2026, 4, 3);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-04-05T15:00:00Z"), KST);

    @BeforeEach
    void setUp() {
        exchangeRateService = new ExchangeRateService(exchangeRateRepository, exchangeRateProvider, FIXED_CLOCK);
    }

    @Nested
    @DisplayName("fetchAndSaveRates()")
    class FetchAndSaveRates {

        @Test
        @DisplayName("Provider 응답을 DB에 저장한다")
        void saves_rates_from_provider() {
            given(exchangeRateProvider.getExchangeRates(DATE))
                    .willReturn(List.of(new FetchedRate(CurrencyCode.USD, new BigDecimal("1300.00"))));

            boolean fetched = exchangeRateService.fetchAndSaveRates(DATE);

            assertThat(fetched).isTrue();
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

            boolean fetched = exchangeRateService.fetchAndSaveRates(DATE);

            assertThat(fetched).isTrue();
            verify(exchangeRateRepository).saveAndFlush(captor.capture());
            assertThat(captor.getValue().getCurrencyCode()).isEqualTo(CurrencyCode.EUR);
        }

        @Test
        @DisplayName("Provider 실패는 배경 수집 경로에서 전파하지 않고 저장을 건너뛴다")
        void skips_when_provider_fails() {
            given(exchangeRateProvider.getExchangeRates(DATE))
                    .willThrow(new BusinessException(ExchangeErrorCode.EXCHANGE_API_LIMIT_EXCEEDED));

            boolean fetched = exchangeRateService.fetchAndSaveRates(DATE);

            assertThat(fetched).isFalse();
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
            LocalDate future = DATE.plusDays(4);

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
    @DisplayName("getSnapshot()")
    class GetSnapshot {

        @Test
        @DisplayName("통화별 기준일 이전 최신 환율을 통화 순서로 반환하고 없는 통화는 건너뛴다")
        void returns_on_or_before_rates_and_skips_missing_currencies() {
            var usd = ExchangeRate.of(CurrencyCode.USD, new BigDecimal("1300.00"), DATE);
            var jpy = ExchangeRate.of(CurrencyCode.JPY, new BigDecimal("900.00"), DATE.minusDays(1));
            given(exchangeRateRepository.findTopByCurrencyCodeAndBaseDateLessThanEqualOrderByBaseDateDesc(
                            CurrencyCode.USD, DATE))
                    .willReturn(Optional.of(usd));
            given(exchangeRateRepository.findTopByCurrencyCodeAndBaseDateLessThanEqualOrderByBaseDateDesc(
                            CurrencyCode.EUR, DATE))
                    .willReturn(Optional.empty());
            given(exchangeRateRepository.findTopByCurrencyCodeAndBaseDateLessThanEqualOrderByBaseDateDesc(
                            CurrencyCode.JPY, DATE))
                    .willReturn(Optional.of(jpy));

            List<ExchangeRate> result = exchangeRateService.getSnapshot(DATE);

            assertThat(result)
                    .extracting(ExchangeRate::getCurrencyCode)
                    .containsExactly(CurrencyCode.USD, CurrencyCode.JPY);

            verify(exchangeRateRepository, never())
                    .findTopByCurrencyCodeAndBaseDateLessThanEqualOrderByBaseDateDesc(CurrencyCode.KRW, DATE);
        }

        @Test
        @DisplayName("미래 날짜 조회 시 BusinessException을 던지고 repository를 호출하지 않는다")
        void throws_for_future_date() {
            LocalDate future = DATE.plusDays(4);

            assertThatThrownBy(() -> exchangeRateService.getSnapshot(future))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(
                            ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo("INVALID_DATE"));

            verify(exchangeRateRepository, never())
                    .findTopByCurrencyCodeAndBaseDateLessThanEqualOrderByBaseDateDesc(any(), any());
        }
    }

    @Nested
    @DisplayName("getRateOnOrBeforeOrOldest()")
    class GetRateOnOrBeforeOrOldest {

        @Test
        @DisplayName("기준일 이전 환율이 있으면 해당 환율을 반환한다")
        void returns_on_or_before_rate_when_exists() {
            var rate = ExchangeRate.of(CurrencyCode.USD, new BigDecimal("1300.00"), DATE.minusDays(1));
            given(exchangeRateRepository.findTopByCurrencyCodeAndBaseDateLessThanEqualOrderByBaseDateDesc(
                            CurrencyCode.USD, DATE))
                    .willReturn(Optional.of(rate));

            ExchangeRate result = exchangeRateService.getRateOnOrBeforeOrOldest(CurrencyCode.USD, DATE);

            assertThat(result).isSameAs(rate);
            verify(exchangeRateRepository, never()).findTopByCurrencyCodeOrderByBaseDateAsc(any());
        }

        @Test
        @DisplayName("기준일 이전 환율이 없으면 가장 오래된 환율로 clamp한다")
        void falls_back_to_oldest_rate_when_no_on_or_before_rate() {
            var oldest = ExchangeRate.of(CurrencyCode.USD, new BigDecimal("1290.00"), DATE.plusDays(1));
            given(exchangeRateRepository.findTopByCurrencyCodeAndBaseDateLessThanEqualOrderByBaseDateDesc(
                            CurrencyCode.USD, DATE))
                    .willReturn(Optional.empty());
            given(exchangeRateRepository.findTopByCurrencyCodeOrderByBaseDateAsc(CurrencyCode.USD))
                    .willReturn(Optional.of(oldest));

            ExchangeRate result = exchangeRateService.getRateOnOrBeforeOrOldest(CurrencyCode.USD, DATE);

            assertThat(result).isSameAs(oldest);

            InOrder inOrder = inOrder(exchangeRateRepository);
            inOrder.verify(exchangeRateRepository)
                    .findTopByCurrencyCodeAndBaseDateLessThanEqualOrderByBaseDateDesc(CurrencyCode.USD, DATE);
            inOrder.verify(exchangeRateRepository).findTopByCurrencyCodeOrderByBaseDateAsc(CurrencyCode.USD);
        }

        @Test
        @DisplayName("통화에 환율이 전혀 없으면 BusinessException을 던진다")
        void throws_when_currency_has_no_rates() {
            given(exchangeRateRepository.findTopByCurrencyCodeAndBaseDateLessThanEqualOrderByBaseDateDesc(
                            CurrencyCode.GBP, DATE))
                    .willReturn(Optional.empty());
            given(exchangeRateRepository.findTopByCurrencyCodeOrderByBaseDateAsc(CurrencyCode.GBP))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() -> exchangeRateService.getRateOnOrBeforeOrOldest(CurrencyCode.GBP, DATE))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(
                            ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo("EXCHANGE_RATE_NOT_FOUND"));
        }

        @Test
        @DisplayName("미래 날짜 조회 시 BusinessException을 던지고 repository를 호출하지 않는다")
        void throws_for_future_date() {
            LocalDate future = DATE.plusDays(4);

            assertThatThrownBy(() -> exchangeRateService.getRateOnOrBeforeOrOldest(CurrencyCode.USD, future))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(
                            ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo("INVALID_DATE"));

            verify(exchangeRateRepository, never())
                    .findTopByCurrencyCodeAndBaseDateLessThanEqualOrderByBaseDateDesc(any(), any());
            verify(exchangeRateRepository, never()).findTopByCurrencyCodeOrderByBaseDateAsc(any());
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
            LocalDate future = DATE.plusDays(4);

            assertThatThrownBy(() -> exchangeRateService.getAllRatesByDate(future))
                    .isInstanceOf(BusinessException.class);

            verify(exchangeRateRepository, never()).findByBaseDate(any());
        }
    }

    @Nested
    @DisplayName("getRatesInRange()")
    class GetRatesInRange {

        @Test
        @DisplayName("유효한 범위는 Repository에 그대로 위임한다")
        void delegates_valid_range() {
            LocalDate to = DATE.plusDays(399);
            var rates = List.of(ExchangeRate.of(CurrencyCode.USD, new BigDecimal("1300.00"), DATE));
            given(exchangeRateRepository.findByBaseDateBetweenOrderByBaseDateAscCurrencyCodeAsc(DATE, to))
                    .willReturn(rates);

            List<ExchangeRate> result = exchangeRateService.getRatesInRange(DATE, to);

            assertThat(result).containsExactlyElementsOf(rates);
            verify(exchangeRateRepository).findByBaseDateBetweenOrderByBaseDateAscCurrencyCodeAsc(DATE, to);
        }

        @Test
        @DisplayName("유효하지 않은 범위는 Repository를 호출하지 않는다")
        void rejects_invalid_range_before_repository_call() {
            assertThatThrownBy(() -> exchangeRateService.getRatesInRange(DATE, DATE.minusDays(1)))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(
                            ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo("INVALID_DATE_RANGE"));

            verify(exchangeRateRepository, never())
                    .findByBaseDateBetweenOrderByBaseDateAscCurrencyCodeAsc(any(), any());
        }

        @Test
        @DisplayName("미래 종료일도 가드 없이 Repository에 그대로 위임한다")
        void delegates_future_to_date() {
            LocalDate futureTo = LocalDate.of(2026, 4, 7);
            given(exchangeRateRepository.findByBaseDateBetweenOrderByBaseDateAscCurrencyCodeAsc(DATE, futureTo))
                    .willReturn(List.of());

            assertThat(exchangeRateService.getRatesInRange(DATE, futureTo)).isEmpty();

            verify(exchangeRateRepository).findByBaseDateBetweenOrderByBaseDateAscCurrencyCodeAsc(DATE, futureTo);
        }
    }

    @Nested
    @DisplayName("getLatestRatesByCurrency()")
    class GetLatestRatesByCurrency {

        @Test
        @DisplayName("Repository의 통화별 최신 환율 목록을 반환한다")
        void returns_latest_rates_by_currency() {
            var rates = List.of(
                    ExchangeRate.of(CurrencyCode.USD, new BigDecimal("1300.00"), DATE),
                    ExchangeRate.of(CurrencyCode.EUR, new BigDecimal("1450.00"), DATE.minusDays(1)));
            given(exchangeRateRepository.findLatestRatesByCurrency()).willReturn(rates);

            List<ExchangeRate> result = exchangeRateService.getLatestRatesByCurrency();

            assertThat(result).containsExactlyElementsOf(rates);
        }
    }
}

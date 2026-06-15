package com.self.multi_currency_household_ledger.exchange.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.self.multi_currency_household_ledger.common.exception.BusinessException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ExchangeRateTest {

    @Nested
    @DisplayName("정적 팩토리 메서드 of()")
    class OfMethod {

        @Test
        @DisplayName("ExchangeRate 객체를 올바르게 생성한다")
        void creates_exchange_rate() {
            LocalDate date = LocalDate.of(2026, 4, 3);
            ExchangeRate rate = ExchangeRate.of(CurrencyCode.USD, new BigDecimal("1300.00"), date);

            assertThat(rate.getCurrencyCode()).isEqualTo(CurrencyCode.USD);
            assertThat(rate.getTts()).isEqualByComparingTo(new BigDecimal("1300.00"));
            assertThat(rate.getBaseDate()).isEqualTo(date);
        }
    }

    @Nested
    @DisplayName("convertToKrw()")
    class ConvertToKrw {

        @Test
        @DisplayName("USD 100달러를 KRW로 변환한다")
        void converts_usd_to_krw() {
            ExchangeRate rate = ExchangeRate.of(
                    CurrencyCode.USD, new BigDecimal("1300.00"), LocalDate.now(ZoneId.of("Asia/Seoul")));

            BigDecimal result = rate.convertToKrw(new BigDecimal("100"));

            assertThat(result).isEqualByComparingTo(new BigDecimal("130000.00"));
        }

        @Test
        @DisplayName("JPY 100엔 단위 환율을 올바르게 변환한다")
        void converts_jpy_to_krw() {
            // tts = 900.00 (100엔당)
            ExchangeRate rate =
                    ExchangeRate.of(CurrencyCode.JPY, new BigDecimal("900.00"), LocalDate.now(ZoneId.of("Asia/Seoul")));

            // 1000엔 → 1000 / 100 * 900 = 9000 KRW
            BigDecimal result = rate.convertToKrw(new BigDecimal("1000"));

            assertThat(result).isEqualByComparingTo(new BigDecimal("9000.00"));
        }

        @Test
        @DisplayName("소수점이 있는 금액을 변환한다")
        void converts_decimal_amount() {
            ExchangeRate rate = ExchangeRate.of(
                    CurrencyCode.EUR, new BigDecimal("1450.50"), LocalDate.now(ZoneId.of("Asia/Seoul")));

            BigDecimal result = rate.convertToKrw(new BigDecimal("50.5"));

            assertThat(result).isEqualByComparingTo(new BigDecimal("73250.25"));
        }
    }

    @Nested
    @DisplayName("assertNotFuture()")
    class AssertNotFuture {

        @Test
        @DisplayName("미래 날짜면 BusinessException을 던진다")
        void throws_for_future_date() {
            assertThatThrownBy(() -> ExchangeRate.assertNotFuture(
                            LocalDate.now(ZoneId.of("Asia/Seoul")).plusDays(1)))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("오늘 날짜는 통과한다")
        void passes_for_today() {
            assertThatCode(() -> ExchangeRate.assertNotFuture(LocalDate.now(ZoneId.of("Asia/Seoul"))))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("과거 날짜는 통과한다")
        void passes_for_past_date() {
            assertThatCode(() -> ExchangeRate.assertNotFuture(
                            LocalDate.now(ZoneId.of("Asia/Seoul")).minusDays(1)))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("convertFromKrw()")
    class ConvertFromKrw {

        @Test
        @DisplayName("KRW 130000원을 USD로 변환한다")
        void converts_krw_to_usd() {
            ExchangeRate rate = ExchangeRate.of(
                    CurrencyCode.USD, new BigDecimal("1300.00"), LocalDate.now(ZoneId.of("Asia/Seoul")));

            BigDecimal result = rate.convertFromKrw(new BigDecimal("130000"));

            assertThat(result).isEqualByComparingTo(new BigDecimal("100.00"));
        }

        @Test
        @DisplayName("KRW를 JPY로 변환할 때 100엔 단위를 반영한다")
        void converts_krw_to_jpy() {
            // tts = 900.00 (100엔당)
            ExchangeRate rate =
                    ExchangeRate.of(CurrencyCode.JPY, new BigDecimal("900.00"), LocalDate.now(ZoneId.of("Asia/Seoul")));

            // 9000 KRW → 9000 / 900 * 100 = 1000엔
            BigDecimal result = rate.convertFromKrw(new BigDecimal("9000"));

            assertThat(result).isEqualByComparingTo(new BigDecimal("1000.00"));
        }
    }
}

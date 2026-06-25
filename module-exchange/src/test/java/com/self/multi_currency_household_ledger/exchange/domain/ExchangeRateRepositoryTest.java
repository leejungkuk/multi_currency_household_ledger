package com.self.multi_currency_household_ledger.exchange.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.self.multi_currency_household_ledger.exchange.TestExchangeApplication;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;

@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Import(TestExchangeApplication.class)
class ExchangeRateRepositoryTest {

    @Autowired
    private ExchangeRateRepository exchangeRateRepository;

    @Autowired
    private TestEntityManager em;

    private static final LocalDate DATE = LocalDate.of(2026, 4, 3);

    @Nested
    @DisplayName("findByCurrencyCodeAndBaseDate()")
    class FindByCurrencyCodeAndBaseDate {

        @Test
        @DisplayName("통화 코드와 날짜로 환율을 조회한다")
        void returns_rate_when_exists() {
            em.persist(ExchangeRate.of(CurrencyCode.USD, new BigDecimal("1300.00"), DATE));
            em.flush();

            Optional<ExchangeRate> result =
                    exchangeRateRepository.findByCurrencyCodeAndBaseDate(CurrencyCode.USD, DATE);

            assertThat(result).isPresent();
            assertThat(result.get().getTts()).isEqualByComparingTo(new BigDecimal("1300.00"));
        }

        @Test
        @DisplayName("일치하는 데이터가 없으면 빈 Optional을 반환한다")
        void returns_empty_when_not_exists() {
            Optional<ExchangeRate> result =
                    exchangeRateRepository.findByCurrencyCodeAndBaseDate(CurrencyCode.USD, DATE);

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("findTopByCurrencyCodeOrderByBaseDateDesc()")
    class FindLatest {

        @Test
        @DisplayName("동일 통화의 가장 최신 환율을 반환한다")
        void returns_latest_rate() {
            em.persist(ExchangeRate.of(CurrencyCode.USD, new BigDecimal("1290.00"), DATE.minusDays(2)));
            em.persist(ExchangeRate.of(CurrencyCode.USD, new BigDecimal("1300.00"), DATE.minusDays(1)));
            em.persist(ExchangeRate.of(CurrencyCode.USD, new BigDecimal("1310.00"), DATE));
            em.persist(ExchangeRate.of(CurrencyCode.EUR, new BigDecimal("1500.00"), DATE));
            em.flush();

            Optional<ExchangeRate> result =
                    exchangeRateRepository.findTopByCurrencyCodeOrderByBaseDateDesc(CurrencyCode.USD);

            assertThat(result).isPresent();
            assertThat(result.get().getBaseDate()).isEqualTo(DATE);
            assertThat(result.get().getTts()).isEqualByComparingTo(new BigDecimal("1310.00"));
        }

        @Test
        @DisplayName("데이터가 없으면 빈 Optional을 반환한다")
        void returns_empty_when_no_data() {
            Optional<ExchangeRate> result =
                    exchangeRateRepository.findTopByCurrencyCodeOrderByBaseDateDesc(CurrencyCode.GBP);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("통화별 가장 최신 기준일의 환율만 조회한다")
        void returns_latest_rates_by_currency() {
            em.persist(ExchangeRate.of(CurrencyCode.USD, new BigDecimal("1290.00"), DATE.minusDays(1)));
            em.persist(ExchangeRate.of(CurrencyCode.USD, new BigDecimal("1300.00"), DATE));
            em.persist(ExchangeRate.of(CurrencyCode.EUR, new BigDecimal("1440.00"), DATE.minusDays(2)));
            em.persist(ExchangeRate.of(CurrencyCode.EUR, new BigDecimal("1450.00"), DATE.minusDays(1)));
            em.flush();

            List<ExchangeRate> result = exchangeRateRepository.findLatestRatesByCurrency();

            assertThat(result)
                    .hasSize(2)
                    .extracting(ExchangeRate::getCurrencyCode, ExchangeRate::getBaseDate)
                    .containsExactlyInAnyOrder(
                            org.assertj.core.groups.Tuple.tuple(CurrencyCode.USD, DATE),
                            org.assertj.core.groups.Tuple.tuple(CurrencyCode.EUR, DATE.minusDays(1)));
        }
    }

    @Nested
    @DisplayName("findTopByCurrencyCodeOrderByBaseDateAsc()")
    class FindOldest {

        @Test
        @DisplayName("동일 통화의 가장 오래된 환율을 반환한다")
        void returns_oldest_rate() {
            em.persist(ExchangeRate.of(CurrencyCode.USD, new BigDecimal("1290.00"), DATE.minusDays(2)));
            em.persist(ExchangeRate.of(CurrencyCode.USD, new BigDecimal("1300.00"), DATE.minusDays(1)));
            em.persist(ExchangeRate.of(CurrencyCode.USD, new BigDecimal("1310.00"), DATE));
            em.persist(ExchangeRate.of(CurrencyCode.EUR, new BigDecimal("1500.00"), DATE.minusDays(3)));
            em.flush();

            Optional<ExchangeRate> result =
                    exchangeRateRepository.findTopByCurrencyCodeOrderByBaseDateAsc(CurrencyCode.USD);

            assertThat(result).isPresent();
            assertThat(result.get().getBaseDate()).isEqualTo(DATE.minusDays(2));
            assertThat(result.get().getTts()).isEqualByComparingTo(new BigDecimal("1290.00"));
        }

        @Test
        @DisplayName("데이터가 없으면 빈 Optional을 반환한다")
        void returns_empty_when_no_data() {
            Optional<ExchangeRate> result =
                    exchangeRateRepository.findTopByCurrencyCodeOrderByBaseDateAsc(CurrencyCode.GBP);

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("findByBaseDate()")
    class FindByBaseDate {

        @Test
        @DisplayName("특정 날짜의 모든 통화 환율을 반환한다")
        void returns_all_rates_for_date() {
            em.persist(ExchangeRate.of(CurrencyCode.USD, new BigDecimal("1300.00"), DATE));
            em.persist(ExchangeRate.of(CurrencyCode.EUR, new BigDecimal("1450.00"), DATE));
            em.persist(ExchangeRate.of(CurrencyCode.JPY, new BigDecimal("900.00"), DATE.minusDays(1)));
            em.flush();

            List<ExchangeRate> result = exchangeRateRepository.findByBaseDate(DATE);

            assertThat(result)
                    .hasSize(2)
                    .extracting(ExchangeRate::getCurrencyCode)
                    .containsExactlyInAnyOrder(CurrencyCode.USD, CurrencyCode.EUR);
        }

        @Test
        @DisplayName("해당 날짜 데이터가 없으면 빈 리스트를 반환한다")
        void returns_empty_list_when_no_data() {
            List<ExchangeRate> result = exchangeRateRepository.findByBaseDate(DATE);

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("BaseEntity 자동 매핑")
    class BaseEntityAuditing {

        @Test
        @DisplayName("저장 시 createdAt/updatedAt이 채워진다")
        void auditing_fields_are_populated() {
            ExchangeRate saved =
                    exchangeRateRepository.save(ExchangeRate.of(CurrencyCode.USD, new BigDecimal("1300.00"), DATE));
            em.flush();

            assertThat(saved.getId()).isNotNull();
            assertThat(saved.getCreatedAt()).isNotNull();
            assertThat(saved.getUpdatedAt()).isNotNull();
        }

        @Test
        @DisplayName("tts는 소수 6자리 정밀도로 저장한다")
        void stores_tts_with_six_decimal_places() {
            ExchangeRate saved =
                    exchangeRateRepository.save(ExchangeRate.of(CurrencyCode.USD, new BigDecimal("1300.123456"), DATE));
            em.flush();
            em.clear();

            ExchangeRate found = exchangeRateRepository.findById(saved.getId()).orElseThrow();

            assertThat(found.getTts()).isEqualByComparingTo(new BigDecimal("1300.123456"));
        }
    }
}

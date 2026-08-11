package com.self.multi_currency_household_ledger.exchange.service;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.self.multi_currency_household_ledger.exchange.TestExchangeApplication;
import com.self.multi_currency_household_ledger.exchange.domain.CurrencyCode;
import com.self.multi_currency_household_ledger.exchange.domain.ExchangeRate;
import com.self.multi_currency_household_ledger.exchange.domain.ExchangeRateRepository;
import com.self.multi_currency_household_ledger.exchange.service.ExchangeRateBackfillService.BackfillStatus;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(classes = TestExchangeApplication.class)
@Import(ExchangeRateBackfillServiceIntegrationTest.FixedClockConfig.class)
class ExchangeRateBackfillServiceIntegrationTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 10);
    private static final WireMockServer WIRE_MOCK =
            new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());

    static {
        WIRE_MOCK.start();
    }

    @DynamicPropertySource
    static void exchangeProperties(DynamicPropertyRegistry registry) {
        registry.add("exchange.eximbank.api-url", () -> WIRE_MOCK.baseUrl() + "/exchangeJSON");
        registry.add("exchange.eximbank.api-key", () -> "test-api-key");
        registry.add("exchange.eximbank.connect-timeout", () -> "2s");
        registry.add("exchange.eximbank.read-timeout", () -> "2s");
    }

    @Autowired
    private ExchangeRateBackfillService backfillService;

    @Autowired
    private ExchangeRateService exchangeRateService;

    @Autowired
    private ExchangeRateRepository exchangeRateRepository;

    @BeforeEach
    void setUp() {
        exchangeRateRepository.deleteAll();
        WIRE_MOCK.resetAll();
    }

    @AfterAll
    static void stopWireMock() {
        WIRE_MOCK.stop();
    }

    @Test
    @DisplayName("완전일만 있는 범위는 외부 API를 호출하지 않는다")
    void complete_range_makes_no_external_calls() {
        saveRates(TODAY, foreignCodes());

        var result = backfillService.backfill(TODAY, TODAY);

        assertThat(result.status()).isEqualTo(BackfillStatus.COMPLETED);
        assertThat(result.filledDays()).isZero();
        assertThat(result.failedDays()).isZero();
        assertThat(result.endDateComplete()).isTrue();
        WIRE_MOCK.verify(0, getRequestedFor(urlPathEqualTo("/exchangeJSON")));
    }

    @Test
    @DisplayName("부분 결손일은 재조회하고 이미 있는 통화는 건너뛰어 빠진 한 통화만 저장한다")
    void partial_gap_fills_only_missing_currency() {
        saveRates(
                TODAY,
                foreignCodes().stream().filter(code -> code != CurrencyCode.NZD).toList());
        stubRates(TODAY, fullResponse());

        var result = backfillService.backfill(TODAY, TODAY);

        assertThat(result.filledDays()).isEqualTo(1);
        assertThat(result.endDateComplete()).isTrue();
        assertThat(exchangeRateRepository.findByBaseDate(TODAY))
                .hasSize(foreignCodes().size());
        assertThat(exchangeRateRepository.findByCurrencyCodeAndBaseDate(CurrencyCode.NZD, TODAY))
                .isPresent();
        verifyRequestedOnce(TODAY);
    }

    @Test
    @DisplayName("0행 결손일은 재조회해 지원 통화 전체를 채운다")
    void empty_gap_fills_all_currencies() {
        stubRates(TODAY, fullResponse());

        var result = backfillService.backfill(TODAY, TODAY);

        assertThat(result.filledDays()).isEqualTo(1);
        assertThat(result.endDateComplete()).isTrue();
        assertThat(exchangeRateRepository.findByBaseDate(TODAY))
                .hasSize(foreignCodes().size());
    }

    @Test
    @DisplayName("종료일에 11종만 저장되면 수집일이지만 완전일로 판정하지 않는다")
    void partially_filled_end_date_remains_incomplete() {
        stubRates(TODAY, responseWithoutNzd());

        var result = backfillService.backfill(TODAY, TODAY);

        assertThat(result.filledDays()).isEqualTo(1);
        assertThat(result.endDateComplete()).isFalse();
        assertThat(exchangeRateRepository.findByBaseDate(TODAY))
                .hasSize(foreignCodes().size() - 1);
    }

    @Test
    @DisplayName("KRW가 섞인 12행은 완전일이 아니므로 빠진 외화를 재조회한다")
    void base_currency_does_not_make_incomplete_day_complete() {
        List<CurrencyCode> elevenForeign =
                foreignCodes().stream().filter(code -> code != CurrencyCode.NZD).toList();
        saveRates(TODAY, elevenForeign);
        saveRates(TODAY, List.of(CurrencyCode.KRW));
        stubRates(TODAY, fullResponse());

        var result = backfillService.backfill(TODAY, TODAY);

        assertThat(result.filledDays()).isEqualTo(1);
        assertThat(result.endDateComplete()).isTrue();
        verifyRequestedOnce(TODAY);
    }

    @Test
    @DisplayName("주말은 결손 후보에서 제외해 외부 API를 호출하지 않는다")
    void weekends_are_not_requested() {
        LocalDate saturday = LocalDate.of(2026, 8, 8);

        var result = backfillService.backfill(saturday, saturday);

        assertThat(result.status()).isEqualTo(BackfillStatus.COMPLETED);
        assertThat(result.endDateComplete()).isFalse();
        WIRE_MOCK.verify(0, getRequestedFor(urlPathEqualTo("/exchangeJSON")));
    }

    @Test
    @DisplayName("과거 결손일에서 쿼터가 소진돼도 종료일은 먼저 수집한다")
    void end_date_is_filled_before_past_quota_abort() {
        LocalDate monday = LocalDate.of(2026, 8, 3);
        stubRates(TODAY, fullResponse());
        stubRates(monday, "[{\"result\":4}]");

        var result = backfillService.backfill(monday, TODAY);

        assertThat(result.status()).isEqualTo(BackfillStatus.QUOTA_ABORTED);
        assertThat(result.filledDays()).isEqualTo(1);
        assertThat(result.endDateComplete()).isTrue();
        assertThat(exchangeRateRepository.findByBaseDate(TODAY))
                .hasSize(foreignCodes().size());
        assertThat(WIRE_MOCK.getAllServeEvents().reversed())
                .extracting(
                        event -> event.getRequest().queryParameter("searchdate").firstValue())
                .containsExactly("20260810", "20260803");
        verifyNotRequested(LocalDate.of(2026, 8, 4));
        verifyNotRequested(LocalDate.of(2026, 8, 5));
        verifyNotRequested(LocalDate.of(2026, 8, 6));
        verifyNotRequested(LocalDate.of(2026, 8, 7));
    }

    @Test
    @DisplayName("result=3이면 AUTH_ABORTED로 즉시 중단한다")
    void auth_error_aborts_immediately() {
        LocalDate monday = LocalDate.of(2026, 8, 3);
        LocalDate tuesday = monday.plusDays(1);
        LocalDate wednesday = monday.plusDays(2);
        stubRates(wednesday, "[{\"result\":3}]");

        var result = backfillService.backfill(monday, wednesday);

        assertThat(result.status()).isEqualTo(BackfillStatus.AUTH_ABORTED);
        assertThat(result.failedDays()).isZero();
        assertThat(result.endDateComplete()).isFalse();
        verifyRequestedOnce(wednesday);
        verifyNotRequested(monday);
        verifyNotRequested(tuesday);
    }

    @Test
    @DisplayName("연속 세 날짜의 일시 실패면 TRANSIENT_ABORTED로 중단한다")
    void three_consecutive_transient_failures_abort() {
        LocalDate monday = LocalDate.of(2026, 8, 3);
        LocalDate tuesday = monday.plusDays(1);
        LocalDate wednesday = monday.plusDays(2);
        stubServerError(monday);
        stubServerError(tuesday);
        stubServerError(wednesday);

        var result = backfillService.backfill(monday, wednesday);

        assertThat(result.status()).isEqualTo(BackfillStatus.TRANSIENT_ABORTED);
        assertThat(result.failedDays()).isEqualTo(3);
        assertThat(result.endDateComplete()).isFalse();
        WIRE_MOCK.verify(3, getRequestedFor(urlPathEqualTo("/exchangeJSON")));
    }

    @Test
    @DisplayName("일시 실패 사이의 빈 응답인 휴일은 연속 실패 카운터를 리셋한다")
    void empty_holiday_response_resets_consecutive_transient_failures() {
        LocalDate monday = LocalDate.of(2026, 8, 3);
        LocalDate tuesday = monday.plusDays(1);
        LocalDate wednesday = monday.plusDays(2);
        LocalDate thursday = monday.plusDays(3);
        LocalDate friday = monday.plusDays(4);
        stubServerError(friday);
        stubServerError(monday);
        stubRates(tuesday, "[]");
        stubServerError(wednesday);
        stubServerError(thursday);

        var result = backfillService.backfill(monday, friday);

        assertThat(result.status()).isEqualTo(BackfillStatus.COMPLETED);
        assertThat(result.failedDays()).isEqualTo(4);
        assertThat(result.endDateComplete()).isFalse();
        assertThat(WIRE_MOCK.getAllServeEvents().reversed())
                .extracting(
                        event -> event.getRequest().queryParameter("searchdate").firstValue())
                .containsExactly("20260807", "20260803", "20260804", "20260805", "20260806");
    }

    @Test
    @DisplayName("일시 실패 사이의 성공 저장은 연속 실패 카운터를 리셋한다")
    void successful_save_resets_consecutive_transient_failures() {
        LocalDate monday = LocalDate.of(2026, 8, 3);
        LocalDate tuesday = monday.plusDays(1);
        LocalDate wednesday = monday.plusDays(2);
        LocalDate thursday = monday.plusDays(3);
        LocalDate friday = monday.plusDays(4);
        stubServerError(friday);
        stubServerError(monday);
        stubRates(tuesday, fullResponse());
        stubServerError(wednesday);
        stubServerError(thursday);

        var result = backfillService.backfill(monday, friday);

        assertThat(result.status()).isEqualTo(BackfillStatus.COMPLETED);
        assertThat(result.filledDays()).isEqualTo(1);
        assertThat(result.failedDays()).isEqualTo(4);
        assertThat(result.endDateComplete()).isFalse();
        assertThat(WIRE_MOCK.getAllServeEvents().reversed())
                .extracting(
                        event -> event.getRequest().queryParameter("searchdate").firstValue())
                .containsExactly("20260807", "20260803", "20260804", "20260805", "20260806");
    }

    @Test
    @DisplayName("여러 날 연속 성공은 종료일 우선으로 모든 평일을 채우고 주말을 건너뛴다")
    void consecutive_successes_fill_every_weekday_in_expected_order() {
        LocalDate monday = LocalDate.of(2026, 8, 3);
        LocalDate saturday = LocalDate.of(2026, 8, 8);
        LocalDate sunday = LocalDate.of(2026, 8, 9);
        stubRates(TODAY, fullResponse());
        for (int day = 0; day < 5; day++) {
            stubRates(monday.plusDays(day), fullResponse());
        }

        var result = backfillService.backfill(monday, TODAY);

        assertThat(result.status()).isEqualTo(BackfillStatus.COMPLETED);
        assertThat(result.filledDays()).isEqualTo(6);
        assertThat(result.failedDays()).isZero();
        assertThat(result.endDateComplete()).isTrue();
        assertThat(WIRE_MOCK.getAllServeEvents().reversed())
                .extracting(
                        event -> event.getRequest().queryParameter("searchdate").firstValue())
                .containsExactly("20260810", "20260803", "20260804", "20260805", "20260806", "20260807");
        verifyNotRequested(saturday);
        verifyNotRequested(sunday);
    }

    @Test
    @DisplayName("빈 응답인 휴일만 남아도 실패로 세지 않고 COMPLETED를 반환한다")
    void empty_holiday_response_remains_completed() {
        stubRates(TODAY, "[]");

        var result = backfillService.backfill(TODAY, TODAY);

        assertThat(result.status()).isEqualTo(BackfillStatus.COMPLETED);
        assertThat(result.filledDays()).isZero();
        assertThat(result.failedDays()).isZero();
        assertThat(result.endDateComplete()).isFalse();
    }

    @Test
    @DisplayName("같은 범위를 두 번 백필하면 두 번째는 외부 호출과 저장이 없다")
    void backfill_is_idempotent() {
        stubRates(TODAY, fullResponse());

        var first = backfillService.backfill(TODAY, TODAY);
        var second = backfillService.backfill(TODAY, TODAY);

        assertThat(first.filledDays()).isEqualTo(1);
        assertThat(second.filledDays()).isZero();
        assertThat(second.endDateComplete()).isTrue();
        assertThat(exchangeRateRepository.findByBaseDate(TODAY))
                .hasSize(foreignCodes().size());
        verifyRequestedOnce(TODAY);
    }

    @Test
    @DisplayName("실제 unique 제약 위반은 재조회로 중복을 확인하고 저장 수 0으로 건너뛴다")
    void duplicate_unique_violation_is_skipped_after_database_lookup() {
        saveRates(TODAY, List.of(CurrencyCode.USD));
        stubRates(TODAY, "[{\"cur_unit\":\"USD\",\"tts\":\"1300.000000\"}]");

        int saved = exchangeRateService.fetchAndSaveRates(TODAY);

        assertThat(saved).isZero();
        assertThat(exchangeRateRepository.findByBaseDate(TODAY)).hasSize(1);
    }

    @Test
    @DisplayName("실제 numeric overflow는 중복 조회 결과가 없으므로 무결성 예외를 전파한다")
    void non_duplicate_integrity_violation_is_propagated() {
        stubRates(TODAY, "[{\"cur_unit\":\"USD\",\"tts\":\"10000000000000.000000\"}]");

        assertThatThrownBy(() -> exchangeRateService.fetchAndSaveRates(TODAY))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(exchangeRateRepository.findByCurrencyCodeAndBaseDate(CurrencyCode.USD, TODAY))
                .isEmpty();
    }

    private void saveRates(LocalDate date, List<CurrencyCode> codes) {
        codes.forEach(code ->
                exchangeRateRepository.saveAndFlush(ExchangeRate.of(code, new BigDecimal("1000.000000"), date)));
    }

    private static List<CurrencyCode> foreignCodes() {
        return Arrays.stream(CurrencyCode.values())
                .filter(code -> !code.isBase())
                .toList();
    }

    private void stubRates(LocalDate date, String body) {
        WIRE_MOCK.stubFor(get(urlPathEqualTo("/exchangeJSON"))
                .withQueryParam("searchdate", equalTo(date.toString().replace("-", "")))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody(body)));
    }

    private void stubServerError(LocalDate date) {
        WIRE_MOCK.stubFor(get(urlPathEqualTo("/exchangeJSON"))
                .withQueryParam("searchdate", equalTo(date.toString().replace("-", "")))
                .willReturn(aResponse().withStatus(500)));
    }

    private void verifyRequestedOnce(LocalDate date) {
        WIRE_MOCK.verify(
                1,
                getRequestedFor(urlPathEqualTo("/exchangeJSON"))
                        .withQueryParam("searchdate", equalTo(date.toString().replace("-", ""))));
    }

    private void verifyNotRequested(LocalDate date) {
        WIRE_MOCK.verify(
                0,
                getRequestedFor(urlPathEqualTo("/exchangeJSON"))
                        .withQueryParam("searchdate", equalTo(date.toString().replace("-", ""))));
    }

    private static String responseWithoutNzd() {
        return """
                [
                  {"cur_unit":"USD","tts":"1300.000000"},
                  {"cur_unit":"EUR","tts":"1450.000000"},
                  {"cur_unit":"JPY(100)","tts":"900.000000"},
                  {"cur_unit":"CNH","tts":"190.000000"},
                  {"cur_unit":"GBP","tts":"1700.000000"},
                  {"cur_unit":"THB","tts":"38.000000"},
                  {"cur_unit":"HKD","tts":"170.000000"},
                  {"cur_unit":"SGD","tts":"980.000000"},
                  {"cur_unit":"IDR(100)","tts":"9.000000"},
                  {"cur_unit":"MYR","tts":"300.000000"},
                  {"cur_unit":"AUD","tts":"850.000000"}
                ]
                """;
    }

    private static String fullResponse() {
        return """
                [
                  {"cur_unit":"USD","tts":"1300.000000"},
                  {"cur_unit":"EUR","tts":"1450.000000"},
                  {"cur_unit":"JPY(100)","tts":"900.000000"},
                  {"cur_unit":"CNH","tts":"190.000000"},
                  {"cur_unit":"GBP","tts":"1700.000000"},
                  {"cur_unit":"THB","tts":"38.000000"},
                  {"cur_unit":"HKD","tts":"170.000000"},
                  {"cur_unit":"SGD","tts":"980.000000"},
                  {"cur_unit":"IDR(100)","tts":"9.000000"},
                  {"cur_unit":"MYR","tts":"300.000000"},
                  {"cur_unit":"AUD","tts":"850.000000"},
                  {"cur_unit":"NZD","tts":"780.000000"}
                ]
                """;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfig {

        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-08-10T02:05:00Z"), ZoneId.of("Asia/Seoul"));
        }
    }
}

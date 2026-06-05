package com.self.multi_currency_household_ledger.exchange.provider;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.self.multi_currency_household_ledger.common.exception.BusinessException;
import com.self.multi_currency_household_ledger.exchange.domain.CurrencyCode;
import com.self.multi_currency_household_ledger.exchange.domain.FetchedRate;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class EximBankExchangeRateProviderTest {

    private WireMockServer wireMock;
    private EximBankExchangeRateProvider provider;

    @BeforeEach
    void setUp() {
        wireMock = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wireMock.start();

        provider = new EximBankExchangeRateProvider(
                RestClient.builder(),
                wireMock.baseUrl() + "/exchangeJSON",
                "test-api-key"
        );
    }

    @AfterEach
    void tearDown() {
        wireMock.stop();
    }

    @Test
    @DisplayName("정상 응답 시 지원 통화만 필터링하여 FetchedRate로 반환한다")
    void returns_supported_currencies_only() {
        wireMock.stubFor(get(urlPathEqualTo("/exchangeJSON"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                [
                                    {"cur_unit":"USD","cur_nm":"미 달러","deal_bas_r":"1,300.50"},
                                    {"cur_unit":"EUR","cur_nm":"유로","deal_bas_r":"1,450.00"},
                                    {"cur_unit":"AED","cur_nm":"아랍에미리트 디르함","deal_bas_r":"354.05"}
                                ]
                                """)));

        List<FetchedRate> result = provider.getExchangeRates(LocalDate.of(2026, 4, 3));

        assertThat(result).hasSize(2);
        assertThat(result.get(0).currencyCode()).isEqualTo(CurrencyCode.USD);
        assertThat(result.get(0).dealBasRate()).isEqualByComparingTo(new BigDecimal("1300.50"));
        assertThat(result.get(1).currencyCode()).isEqualTo(CurrencyCode.EUR);
    }

    @Test
    @DisplayName("빈 응답 시 빈 리스트를 반환한다")
    void returns_empty_list_when_no_data() {
        wireMock.stubFor(get(urlPathEqualTo("/exchangeJSON"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("[]")));

        List<FetchedRate> result = provider.getExchangeRates(LocalDate.of(2026, 4, 3));

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("API 서버 에러 시 BusinessException을 던진다")
    void throws_exception_on_server_error() {
        wireMock.stubFor(get(urlPathEqualTo("/exchangeJSON"))
                .willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> provider.getExchangeRates(LocalDate.of(2026, 4, 3)))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo("EXCHANGE_API_ERROR"));
    }

    @Test
    @DisplayName("JPY(100) 단위 통화도 올바르게 파싱한다")
    void parses_jpy_with_unit() {
        wireMock.stubFor(get(urlPathEqualTo("/exchangeJSON"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                [{"cur_unit":"JPY(100)","cur_nm":"일본 엔","deal_bas_r":"900.00"}]
                                """)));

        List<FetchedRate> result = provider.getExchangeRates(LocalDate.of(2026, 4, 3));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).currencyCode()).isEqualTo(CurrencyCode.JPY);
        assertThat(result.get(0).dealBasRate()).isEqualByComparingTo(new BigDecimal("900.00"));
    }

    @Test
    @DisplayName("deal_bas_r가 null인 통화는 건너뛰고 정상 통화만 반환한다")
    void skips_null_deal_bas_r() {
        wireMock.stubFor(get(urlPathEqualTo("/exchangeJSON"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                [
                                    {"cur_unit":"USD","cur_nm":"미 달러","deal_bas_r":null},
                                    {"cur_unit":"EUR","cur_nm":"유로","deal_bas_r":"1,450.00"}
                                ]
                                """)));

        List<FetchedRate> result = provider.getExchangeRates(LocalDate.of(2026, 4, 3));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).currencyCode()).isEqualTo(CurrencyCode.EUR);
    }

    @Test
    @DisplayName("deal_bas_r가 0이하인 통화는 건너뛴다")
    void skips_zero_or_negative_deal_bas_r() {
        wireMock.stubFor(get(urlPathEqualTo("/exchangeJSON"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                [
                                    {"cur_unit":"USD","cur_nm":"미 달러","deal_bas_r":"0"},
                                    {"cur_unit":"EUR","cur_nm":"유로","deal_bas_r":"1,450.00"}
                                ]
                                """)));

        List<FetchedRate> result = provider.getExchangeRates(LocalDate.of(2026, 4, 3));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).currencyCode()).isEqualTo(CurrencyCode.EUR);
    }

    @Test
    @DisplayName("deal_bas_r가 숫자가 아닌 통화는 건너뛴다")
    void skips_non_numeric_deal_bas_r() {
        wireMock.stubFor(get(urlPathEqualTo("/exchangeJSON"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                [
                                    {"cur_unit":"USD","cur_nm":"미 달러","deal_bas_r":"N/A"},
                                    {"cur_unit":"EUR","cur_nm":"유로","deal_bas_r":"1,450.00"}
                                ]
                                """)));

        List<FetchedRate> result = provider.getExchangeRates(LocalDate.of(2026, 4, 3));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).currencyCode()).isEqualTo(CurrencyCode.EUR);
    }
}

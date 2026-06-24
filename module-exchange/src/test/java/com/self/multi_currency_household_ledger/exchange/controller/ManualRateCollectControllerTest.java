package com.self.multi_currency_household_ledger.exchange.controller;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.self.multi_currency_household_ledger.exchange.domain.CurrencyCode;
import com.self.multi_currency_household_ledger.exchange.domain.ExchangeRate;
import com.self.multi_currency_household_ledger.exchange.service.ExchangeRateService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.jpa.mapping.JpaMetamodelMappingContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = ManualRateCollectController.class)
@ActiveProfiles("local")
class ManualRateCollectControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ExchangeRateService exchangeRateService;

    @MockitoBean
    private Clock clock;

    @MockitoBean
    @SuppressWarnings("UnusedVariable")
    private JpaMetamodelMappingContext jpaMetamodelMappingContext;

    private static final LocalDate DATE = LocalDate.of(2026, 4, 3);
    private static final LocalDate TODAY = LocalDate.of(2026, 4, 6);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Test
    @DisplayName("POST /api/v1/exchange-rates/collect date 지정 시 해당 날짜 환율을 수집하고 저장된 목록을 반환한다")
    void collect_with_date_fetches_and_returns_rates() throws Exception {
        givenToday(TODAY);
        var rates = List.of(
                ExchangeRate.of(CurrencyCode.USD, new BigDecimal("1300.00"), DATE),
                ExchangeRate.of(CurrencyCode.EUR, new BigDecimal("1450.00"), DATE));
        given(exchangeRateService.fetchAndSaveRates(DATE)).willReturn(true);
        given(exchangeRateService.getAllRatesByDate(DATE)).willReturn(rates);

        mockMvc.perform(post("/api/v1/exchange-rates/collect").param("date", "2026-04-03"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].currencyCode").value("USD"))
                .andExpect(jsonPath("$.data[0].currencyName").value("미 달러"))
                .andExpect(jsonPath("$.data[0].tts").value(1300.00))
                .andExpect(jsonPath("$.data[0].baseDate").value("2026-04-03"))
                .andExpect(jsonPath("$.data[0].stale").value(false));

        verify(exchangeRateService).fetchAndSaveRates(DATE);
    }

    @Test
    @DisplayName("POST /api/v1/exchange-rates/collect date 생략 시 KST 오늘 날짜로 수집한다")
    void collect_without_date_fetches_today_from_clock() throws Exception {
        givenToday(TODAY);
        var rates = List.of(ExchangeRate.of(CurrencyCode.USD, new BigDecimal("1300.00"), TODAY));
        given(exchangeRateService.fetchAndSaveRates(TODAY)).willReturn(true);
        given(exchangeRateService.getAllRatesByDate(TODAY)).willReturn(rates);

        mockMvc.perform(post("/api/v1/exchange-rates/collect"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].baseDate").value("2026-04-06"))
                .andExpect(jsonPath("$.data[0].stale").value(false));

        verify(exchangeRateService).fetchAndSaveRates(TODAY);
    }

    @Test
    @DisplayName("POST /api/v1/exchange-rates/collect fetch 실패 시 500 EXCHANGE_API_ERROR를 반환하고 목록 조회를 하지 않는다")
    void collect_returns_500_when_fetch_fails() throws Exception {
        givenToday(TODAY);
        given(exchangeRateService.fetchAndSaveRates(DATE)).willReturn(false);

        mockMvc.perform(post("/api/v1/exchange-rates/collect").param("date", "2026-04-03"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("EXCHANGE_API_ERROR"));

        verify(exchangeRateService).fetchAndSaveRates(DATE);
        verify(exchangeRateService, never()).getAllRatesByDate(DATE);
    }

    @Test
    @DisplayName("POST /api/v1/exchange-rates/collect 미래 날짜는 fetch 전에 400 INVALID_DATE로 차단한다")
    void collect_returns_400_for_future_date_before_fetch() throws Exception {
        givenToday(TODAY);
        LocalDate futureDate = LocalDate.of(2026, 4, 7);

        mockMvc.perform(post("/api/v1/exchange-rates/collect").param("date", "2026-04-07"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("INVALID_DATE"));

        verify(exchangeRateService, never()).fetchAndSaveRates(futureDate);
    }

    @Test
    @DisplayName("POST /api/v1/exchange-rates/collect date 형식이 잘못되면 400 INVALID_PARAMETER를 반환한다")
    void collect_returns_400_for_invalid_date_format() throws Exception {
        mockMvc.perform(post("/api/v1/exchange-rates/collect").param("date", "20260403"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    }

    private void givenToday(LocalDate today) {
        given(clock.instant()).willReturn(today.atStartOfDay(KST).toInstant());
        given(clock.getZone()).willReturn(KST);
    }
}

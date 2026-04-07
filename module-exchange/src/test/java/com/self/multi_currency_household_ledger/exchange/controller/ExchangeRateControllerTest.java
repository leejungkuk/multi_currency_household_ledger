package com.self.multi_currency_household_ledger.exchange.controller;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.self.multi_currency_household_ledger.common.exception.BusinessException;
import com.self.multi_currency_household_ledger.common.exception.GlobalExceptionHandler;
import com.self.multi_currency_household_ledger.exchange.domain.CurrencyCode;
import com.self.multi_currency_household_ledger.exchange.domain.ExchangeRate;
import com.self.multi_currency_household_ledger.exchange.service.ExchangeRateService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = ExchangeRateController.class)
class ExchangeRateControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ExchangeRateService exchangeRateService;

    private static final LocalDate DATE = LocalDate.of(2026, 4, 3);

    @Test
    @DisplayName("GET /api/exchange-rates?date= 특정 날짜 전체 환율을 반환한다")
    void getRatesByDate_returns_all_rates() throws Exception {
        var rates = List.of(
                ExchangeRate.of(CurrencyCode.USD, new BigDecimal("1300.00"), DATE),
                ExchangeRate.of(CurrencyCode.EUR, new BigDecimal("1450.00"), DATE)
        );
        given(exchangeRateService.getAllRatesByDate(DATE)).willReturn(rates);

        mockMvc.perform(get("/api/exchange-rates").param("date", "2026-04-03"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].currencyCode").value("USD"))
                .andExpect(jsonPath("$[0].currencyName").value("미 달러"))
                .andExpect(jsonPath("$[0].dealBasRate").value(1300.00))
                .andExpect(jsonPath("$[0].baseDate").value("2026-04-03"));
    }

    @Test
    @DisplayName("GET /api/exchange-rates/{currencyCode} 특정 통화 최신 환율을 반환한다")
    void getLatestRate_returns_rate() throws Exception {
        var rate = ExchangeRate.of(CurrencyCode.USD, new BigDecimal("1300.00"), DATE);
        given(exchangeRateService.getLatestRate(CurrencyCode.USD)).willReturn(rate);

        mockMvc.perform(get("/api/exchange-rates/USD"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currencyCode").value("USD"))
                .andExpect(jsonPath("$.dealBasRate").value(1300.00));
    }

    @Test
    @DisplayName("지원하지 않는 통화 코드로 요청하면 400을 반환한다")
    void getLatestRate_returns_400_for_invalid_currency() throws Exception {
        mockMvc.perform(get("/api/exchange-rates/INVALID"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/exchange-rates/fetch 환율 가져오기를 트리거한다")
    void fetchRates_triggers_fetch() throws Exception {
        mockMvc.perform(post("/api/exchange-rates/fetch").param("date", "2026-04-03"))
                .andExpect(status().isOk());

        verify(exchangeRateService).fetchAndSaveRates(DATE);
    }

    @Test
    @DisplayName("환율 데이터가 없으면 400을 반환한다")
    void getLatestRate_returns_400_when_not_found() throws Exception {
        given(exchangeRateService.getLatestRate(CurrencyCode.GBP))
                .willThrow(new BusinessException("EXCHANGE_RATE_NOT_FOUND", "GBP 환율 정보가 존재하지 않습니다."));

        mockMvc.perform(get("/api/exchange-rates/GBP"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EXCHANGE_RATE_NOT_FOUND"));
    }
}

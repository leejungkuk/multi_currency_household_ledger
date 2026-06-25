package com.self.multi_currency_household_ledger.exchange.controller;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.self.multi_currency_household_ledger.common.exception.BusinessException;
import com.self.multi_currency_household_ledger.exchange.domain.CurrencyCode;
import com.self.multi_currency_household_ledger.exchange.domain.ExchangeRate;
import com.self.multi_currency_household_ledger.exchange.exception.ExchangeErrorCode;
import com.self.multi_currency_household_ledger.exchange.service.ExchangeRateService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.jpa.mapping.JpaMetamodelMappingContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = ExchangeRateController.class)
class ExchangeRateControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ExchangeRateService exchangeRateService;

    @MockitoBean
    private Clock clock;

    @MockitoBean
    @SuppressWarnings("UnusedVariable") // 직접 참조하지 않지만 @WebMvcTest 컨텍스트 기동(JPA Auditing)에 필요한 주입 필드
    private JpaMetamodelMappingContext jpaMetamodelMappingContext;

    private static final LocalDate DATE = LocalDate.of(2026, 4, 3);
    private static final LocalDate TODAY = LocalDate.of(2026, 4, 6);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDateTime FETCHED_AT = LocalDateTime.of(2026, 4, 3, 11, 5);

    @Test
    @DisplayName("GET /api/v1/exchange-rates?date= 특정 날짜 전체 환율을 ApiResponse 봉투로 반환한다")
    void getRatesByDate_returns_all_rates() throws Exception {
        var rates = List.of(
                ExchangeRate.of(CurrencyCode.USD, new BigDecimal("1300.00"), DATE),
                ExchangeRate.of(CurrencyCode.EUR, new BigDecimal("1450.00"), DATE));
        given(exchangeRateService.getAllRatesByDate(DATE)).willReturn(rates);

        mockMvc.perform(get("/api/v1/exchange-rates").param("date", "2026-04-03"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].currencyCode").value("USD"))
                .andExpect(jsonPath("$.data[0].currencyName").value("미 달러"))
                .andExpect(jsonPath("$.data[0].tts").value(1300.00))
                .andExpect(jsonPath("$.data[0].baseDate").value("2026-04-03"))
                .andExpect(jsonPath("$.data[0].stale").value(false));
    }

    @Test
    @DisplayName("GET /api/v1/exchange-rates/snapshot?date= 통화별 기준일 이전 최신 환율을 ApiResponse 봉투로 반환한다")
    void getSnapshot_returns_on_or_before_rates() throws Exception {
        LocalDate requestedDate = LocalDate.of(2026, 4, 5);
        var rates = List.of(
                ExchangeRate.of(CurrencyCode.USD, new BigDecimal("1300.00"), DATE),
                ExchangeRate.of(CurrencyCode.EUR, new BigDecimal("1450.00"), requestedDate));
        given(exchangeRateService.getSnapshot(requestedDate)).willReturn(rates);

        mockMvc.perform(get("/api/v1/exchange-rates/snapshot").param("date", "2026-04-05"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].currencyCode").value("USD"))
                .andExpect(jsonPath("$.data[0].currencyName").value("미 달러"))
                .andExpect(jsonPath("$.data[0].tts").value(1300.00))
                .andExpect(jsonPath("$.data[0].baseDate").value("2026-04-03"))
                .andExpect(jsonPath("$.data[0].stale").value(true))
                .andExpect(jsonPath("$.data[1].currencyCode").value("EUR"))
                .andExpect(jsonPath("$.data[1].baseDate").value("2026-04-05"))
                .andExpect(jsonPath("$.data[1].stale").value(false));
    }

    @Test
    @DisplayName("GET /api/v1/exchange-rates/snapshot date 생략 시 KST 오늘 기준 snapshot을 반환한다")
    void getSnapshot_uses_today_when_date_omitted() throws Exception {
        given(clock.instant()).willReturn(Instant.parse("2026-04-05T15:00:00Z"));
        given(clock.getZone()).willReturn(KST);
        var rate = ExchangeRate.of(CurrencyCode.USD, new BigDecimal("1300.00"), TODAY);
        given(exchangeRateService.getSnapshot(TODAY)).willReturn(List.of(rate));

        mockMvc.perform(get("/api/v1/exchange-rates/snapshot"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].currencyCode").value("USD"))
                .andExpect(jsonPath("$.data[0].stale").value(false));

        verify(exchangeRateService).getSnapshot(TODAY);
    }

    @Test
    @DisplayName("GET /api/v1/exchange-rates/snapshot date 형식이 잘못되면 400과 ErrorResponse를 반환한다")
    void getSnapshot_returns_400_for_invalid_date_format() throws Exception {
        mockMvc.perform(get("/api/v1/exchange-rates/snapshot").param("date", "20260403"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    }

    @Test
    @DisplayName("GET /api/v1/exchange-rates/{currencyCode} date 생략 시 최신 환율을 반환한다")
    void getRate_returns_latest_when_date_omitted() throws Exception {
        // stale 판정 기준일이 KST 오늘이므로, 결정적 단언을 위해 기준일=오늘 환율을 반환
        given(clock.instant()).willReturn(Instant.parse("2026-04-05T15:00:00Z"));
        given(clock.getZone()).willReturn(KST);
        var rate = ExchangeRate.of(CurrencyCode.USD, new BigDecimal("1300.00"), TODAY);
        given(exchangeRateService.getLatestRate(CurrencyCode.USD)).willReturn(rate);

        mockMvc.perform(get("/api/v1/exchange-rates/USD"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.currencyCode").value("USD"))
                .andExpect(jsonPath("$.data.tts").value(1300.00))
                .andExpect(jsonPath("$.data.stale").value(false));
    }

    @Test
    @DisplayName("GET /api/v1/exchange-rates/{currencyCode}?date= 지정일 환율을 조회하고 fallback이면 stale=true")
    void getRate_returns_rate_on_or_before_requested_date() throws Exception {
        var rate = ExchangeRate.of(CurrencyCode.USD, new BigDecimal("1300.00"), DATE);
        given(exchangeRateService.getRateOnOrBefore(CurrencyCode.USD, LocalDate.of(2026, 4, 5)))
                .willReturn(rate);

        mockMvc.perform(get("/api/v1/exchange-rates/USD").param("date", "2026-04-05"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.baseDate").value("2026-04-03"))
                .andExpect(jsonPath("$.data.stale").value(true));
    }

    @Test
    @DisplayName("GET /api/v1/exchange-rates/status 는 통화별 수집 상태와 last_updated만 반환한다")
    void getStatus_returns_currency_status_without_stale_flags() throws Exception {
        var usd = exchangeRate(CurrencyCode.USD, "1300.00", DATE, FETCHED_AT);
        var eur = exchangeRate(CurrencyCode.EUR, "1450.00", DATE.minusDays(1), FETCHED_AT.plusMinutes(1));
        given(exchangeRateService.getLatestRatesByCurrency()).willReturn(List.of(usd, eur));

        mockMvc.perform(get("/api/v1/exchange-rates/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.rates.length()").value(2))
                .andExpect(jsonPath("$.data.rates[0].currency_code").value("USD"))
                .andExpect(jsonPath("$.data.rates[0].base_date").value("2026-04-03"))
                .andExpect(jsonPath("$.data.rates[0].fetched_at").value("2026-04-03T11:05:00"))
                .andExpect(jsonPath("$.data.last_updated").value("2026-04-03T11:06:00"))
                .andExpect(jsonPath("$.data.rates[0].stale").doesNotExist())
                .andExpect(jsonPath("$.data.rates[0].fallbackStale").doesNotExist())
                .andExpect(jsonPath("$.data.rates[0].fallback_stale").doesNotExist());
    }

    @Test
    @DisplayName("지원하지 않는 통화 코드로 요청하면 400을 반환한다")
    void getLatestRate_returns_400_for_invalid_currency() throws Exception {
        mockMvc.perform(get("/api/v1/exchange-rates/INVALID")).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("date 파라미터 형식이 잘못되면 400과 ErrorResponse(봉투 아님)를 반환한다")
    void getRate_returns_400_for_invalid_date_format() throws Exception {
        mockMvc.perform(get("/api/v1/exchange-rates/USD").param("date", "not-a-date"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    }

    @Test
    @DisplayName("GET /api/v1/exchange-rates date 형식이 잘못되면 400과 ErrorResponse를 반환한다")
    void getRatesByDate_returns_400_for_invalid_date_format() throws Exception {
        mockMvc.perform(get("/api/v1/exchange-rates").param("date", "20260403"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    }

    @Test
    @DisplayName("환율 데이터가 없으면 400을 반환한다")
    void getLatestRate_returns_400_when_not_found() throws Exception {
        given(exchangeRateService.getLatestRate(CurrencyCode.GBP))
                .willThrow(new BusinessException(ExchangeErrorCode.EXCHANGE_RATE_NOT_FOUND));

        mockMvc.perform(get("/api/v1/exchange-rates/GBP"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EXCHANGE_RATE_NOT_FOUND"));
    }

    private static ExchangeRate exchangeRate(
            CurrencyCode currencyCode, String tts, LocalDate baseDate, LocalDateTime fetchedAt) {
        ExchangeRate rate = ExchangeRate.of(currencyCode, new BigDecimal(tts), baseDate);
        ReflectionTestUtils.setField(rate, "createdAt", fetchedAt);
        return rate;
    }
}

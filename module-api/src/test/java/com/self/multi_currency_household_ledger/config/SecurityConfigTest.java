package com.self.multi_currency_household_ledger.config;

import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.self.multi_currency_household_ledger.exchange.controller.ExchangeRateController;
import com.self.multi_currency_household_ledger.exchange.domain.CurrencyCode;
import com.self.multi_currency_household_ledger.exchange.domain.ExchangeRate;
import com.self.multi_currency_household_ledger.exchange.service.ExchangeRateService;
import com.self.multi_currency_household_ledger.ledger.controller.CatalogController;
import com.self.multi_currency_household_ledger.ledger.dto.AssetResponse;
import com.self.multi_currency_household_ledger.ledger.service.CatalogService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.mapping.JpaMetamodelMappingContext;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = {CatalogController.class, ExchangeRateController.class})
@Import(SecurityConfig.class)
@TestPropertySource(
        properties = {
            "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://example.supabase.co/auth/v1",
            "woni.security.jwt.audience=authenticated",
            "woni.security.cors.allowed-origins=http://localhost:3000"
        })
class SecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    @SuppressWarnings("UnusedVariable")
    private JwtDecoder jwtDecoder;

    @MockitoBean
    @SuppressWarnings("UnusedVariable")
    private JpaMetamodelMappingContext jpaMetamodelMappingContext;

    @MockitoBean
    private CatalogService catalogService;

    @MockitoBean
    private ExchangeRateService exchangeRateService;

    @MockitoBean
    private Clock clock;

    @BeforeEach
    void setUpClock() {
        given(clock.instant()).willReturn(Instant.parse("2026-04-03T02:05:00Z"));
        given(clock.getZone()).willReturn(ZoneId.of("Asia/Seoul"));
    }

    @Test
    @DisplayName("보호된 /api/v1 엔드포인트는 토큰 없이 401 ErrorResponse를 반환한다")
    void protected_endpoint_without_token_returns_401() throws Exception {
        mockMvc.perform(get("/api/v1/assets"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    @DisplayName("유효한 mock JWT가 있으면 보호된 /api/v1 엔드포인트에 접근할 수 있다")
    void protected_endpoint_with_mock_jwt_passes_authentication() throws Exception {
        given(catalogService.getAssets()).willReturn(List.of(new AssetResponse(3L, "CASH", "현금", "Cash", 3)));

        mockMvc.perform(get("/api/v1/assets")
                        .with(jwt().jwt(token -> token.subject("00000000-0000-0000-0000-000000000001")
                                .audience(List.of("authenticated")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].code").value("CASH"));
    }

    @Test
    @DisplayName("GET /api/v1/exchange-rates 는 토큰 없이 접근할 수 있다")
    void exchange_rates_collection_without_token_is_public() throws Exception {
        LocalDate requestDate = LocalDate.of(2026, 4, 3);
        given(exchangeRateService.getAllRatesByDate(requestDate))
                .willReturn(List.of(
                        exchangeRate(CurrencyCode.USD, "1300.00", requestDate, LocalDateTime.of(2026, 4, 3, 11, 5))));

        mockMvc.perform(get("/api/v1/exchange-rates").param("date", "2026-04-03"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].currencyCode").value("USD"));
    }

    @Test
    @DisplayName("GET /api/v1/exchange-rates/snapshot 은 토큰 없이 접근할 수 있다")
    void exchange_rate_snapshot_without_token_is_public() throws Exception {
        LocalDate today = LocalDate.of(2026, 4, 3);
        given(exchangeRateService.getSnapshot(today))
                .willReturn(
                        List.of(exchangeRate(CurrencyCode.USD, "1300.00", today, LocalDateTime.of(2026, 4, 3, 11, 5))));

        mockMvc.perform(get("/api/v1/exchange-rates/snapshot"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].currencyCode").value("USD"));
    }

    @Test
    @DisplayName("GET /api/v1/exchange-rates/status 는 토큰 없이 접근할 수 있다")
    void exchange_rate_status_without_token_is_public() throws Exception {
        given(exchangeRateService.getLatestRatesByCurrency())
                .willReturn(List.of(exchangeRate(
                        CurrencyCode.USD, "1300.00", LocalDate.of(2026, 4, 3), LocalDateTime.of(2026, 4, 3, 11, 5))));

        mockMvc.perform(get("/api/v1/exchange-rates/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.rates[0].currency_code").value("USD"));
    }

    @Test
    @DisplayName("POST /api/v1/exchange-rates/collect 는 토큰 없이는 401을 반환한다")
    void exchange_rate_collect_without_token_returns_401() throws Exception {
        mockMvc.perform(post("/api/v1/exchange-rates/collect"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("GET /api/v1/ledgers/** 는 토큰 없이는 401을 반환한다")
    void ledger_endpoint_without_token_returns_401() throws Exception {
        mockMvc.perform(get("/api/v1/ledgers/summary"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("GET /api/v1/exchange-rates/status 는 유효한 mock JWT가 있으면 접근할 수 있다")
    void exchange_rate_status_with_mock_jwt_passes_authentication() throws Exception {
        given(exchangeRateService.getLatestRatesByCurrency())
                .willReturn(List.of(exchangeRate(
                        CurrencyCode.USD, "1300.00", LocalDate.of(2026, 4, 3), LocalDateTime.of(2026, 4, 3, 11, 5))));

        mockMvc.perform(get("/api/v1/exchange-rates/status")
                        .with(jwt().jwt(token -> token.subject("00000000-0000-0000-0000-000000000001")
                                .audience(List.of("authenticated")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.rates[0].currency_code").value("USD"));
    }

    private static ExchangeRate exchangeRate(
            CurrencyCode currencyCode, String tts, LocalDate baseDate, LocalDateTime fetchedAt) {
        ExchangeRate rate = ExchangeRate.of(currencyCode, new BigDecimal(tts), baseDate);
        ReflectionTestUtils.setField(rate, "createdAt", fetchedAt);
        return rate;
    }
}

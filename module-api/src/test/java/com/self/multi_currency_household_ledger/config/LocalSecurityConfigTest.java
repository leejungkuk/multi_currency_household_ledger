package com.self.multi_currency_household_ledger.config;

import static org.hamcrest.Matchers.not;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.self.multi_currency_household_ledger.exchange.controller.ManualRateCollectController;
import com.self.multi_currency_household_ledger.exchange.service.ExchangeRateService;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.mapping.JpaMetamodelMappingContext;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = ManualRateCollectController.class)
@Import({SecurityConfig.class, LocalSecurityConfig.class})
@ActiveProfiles("local")
@TestPropertySource(
        properties = {
            "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://example.supabase.co/auth/v1",
            "woni.security.jwt.audience=authenticated",
            "woni.security.cors.allowed-origins=http://localhost:3000"
        })
class LocalSecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    @SuppressWarnings("UnusedVariable")
    private JwtDecoder jwtDecoder;

    @MockitoBean
    @SuppressWarnings("UnusedVariable")
    private JpaMetamodelMappingContext jpaMetamodelMappingContext;

    @MockitoBean
    private ExchangeRateService exchangeRateService;

    @MockitoBean
    private Clock clock;

    private static final LocalDate TODAY = LocalDate.of(2026, 4, 6);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Test
    @DisplayName("local 프로파일에서 POST /collect 는 토큰 없이 호출된다(dev 시드 도구 인증 면제)")
    void collect_is_permitted_without_token_in_local() throws Exception {
        given(clock.instant()).willReturn(TODAY.atStartOfDay(KST).toInstant());
        given(clock.getZone()).willReturn(KST);
        given(exchangeRateService.fetchAndSaveRates(TODAY)).willReturn(true);
        given(exchangeRateService.getAllRatesByDate(TODAY)).willReturn(List.of());

        mockMvc.perform(post("/api/v1/exchange-rates/collect"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("local 이어도 collect 외 경로는 deny-by-default 로 토큰 없이는 401 이다(면제 범위 한정)")
    void non_collect_path_still_requires_auth_in_local() throws Exception {
        mockMvc.perform(get("/api/v1/exchange-rates/2026-04-06"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("local 이어도 collect 경로의 POST 외 메서드(GET)는 메인 체인으로 떨어져 401 이다(메서드 한정)")
    void non_post_on_collect_path_still_requires_auth_in_local() throws Exception {
        mockMvc.perform(get("/api/v1/exchange-rates/collect"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("local 에서 OpenAPI 문서 경로는 토큰 없이 보안 체인을 통과한다(Swagger UI 사용 가능)")
    void api_docs_path_is_permitted_without_token_in_local() throws Exception {
        // @WebMvcTest 슬라이스엔 springdoc 핸들러가 없어 downstream 상태(404/500)는 의미가 없다. 핵심은 인증에
        // 막히지 않는다는 점(401/403 아님 = local 보안 체인이 통과시킴). 면제가 빠지면 메인 체인으로 떨어져 401 이 된다.
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().is(not(HttpStatus.UNAUTHORIZED.value())))
                .andExpect(status().is(not(HttpStatus.FORBIDDEN.value())));
    }
}

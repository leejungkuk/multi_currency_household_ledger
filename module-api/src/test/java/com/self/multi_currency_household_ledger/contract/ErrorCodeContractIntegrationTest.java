package com.self.multi_currency_household_ledger.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 실제 컨트롤러 선언({@code @ApiErrorCodes})이 {@code /v3/api-docs} 의 {@code x-error-codes} 로 나가는지 단언한다. 일반
 * {@code test} 태스크에서 돈다 — 빈 배선 누락(키 없음)·모르는 코드(문서 생성 실패)·선언 변경(값 다름)을 스냅샷 갱신 없이도 잡는다.
 * 부팅 방식은 {@link OpenApiSnapshotTest} 와 같다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Import(ErrorCodeContractIntegrationTest.TestConfig.class)
@TestPropertySource(
        properties = {
            "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://example.supabase.co/auth/v1",
            "exchange.eximbank.api-key=test-api-key"
        })
class ErrorCodeContractIntegrationTest {

    private static final List<String> COMMON = List.of(
            "INTERNAL_ERROR",
            "INVALID_PARAMETER",
            "MALFORMED_REQUEST",
            "TOO_MANY_REQUESTS",
            "UNAUTHORIZED",
            "VALIDATION_ERROR");

    private static final String BUDGETS = "$.paths['/api/v1/budgets']";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    @SuppressWarnings("UnusedVariable")
    private JwtDecoder jwtDecoder;

    @Test
    void budgetOperationsDeclareTheirErrorCodes() throws Exception {
        mockMvc.perform(get("/v3/api-docs").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath(BUDGETS + ".get['x-error-codes']", equalTo(withCommon())))
                .andExpect(jsonPath(
                        BUDGETS + ".put['x-error-codes']",
                        equalTo(withCommon(
                                "REQUEST_BODY_TOO_LARGE",
                                "BUDGET_MONTH_OUT_OF_RANGE",
                                "CATEGORY_NOT_FOUND",
                                "BUDGET_INVALID_ALLOCATION",
                                "BUDGET_TOTAL_REQUIRED",
                                "BUDGET_INVALID_AMOUNT",
                                "BUDGET_ALLOCATION_EXCEEDS_TOTAL",
                                "CONCURRENT_MODIFICATION"))))
                .andExpect(jsonPath(
                        BUDGETS + ".delete['x-error-codes']",
                        equalTo(withCommon("BUDGET_MONTH_OUT_OF_RANGE", "CONCURRENT_MODIFICATION"))));
    }

    @Test
    void exchangeRateOperationsDeclareTheirErrorCodes() throws Exception {
        mockMvc.perform(get("/v3/api-docs").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath(exchangeRateGetCodes(""), equalTo(withCommon("INVALID_DATE"))))
                .andExpect(jsonPath(exchangeRateGetCodes("/range"), equalTo(withCommon("INVALID_DATE_RANGE"))))
                .andExpect(jsonPath(exchangeRateGetCodes("/snapshot"), equalTo(withCommon("INVALID_DATE"))))
                .andExpect(jsonPath(
                        exchangeRateGetCodes("/{currencyCode}"),
                        equalTo(withCommon("EXCHANGE_RATE_NOT_FOUND", "INVALID_DATE"))))
                .andExpect(jsonPath(exchangeRateGetCodes("/status"), equalTo(withCommon())));
    }

    // 키 = 전부 선언됨. 예산 밖에 키가 생기면(롤아웃 순서 위반) 여기서 빨개진다 — 다음 PR 이 선언하면 이 집합을 같이 늘린다.
    @Test
    void undeclaredOperationHasNoErrorCodesKey() throws Exception {
        JsonNode paths = objectMapper
                .readTree(mockMvc.perform(get("/v3/api-docs").with(jwt()))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .path("paths");

        // 예산 밖 오퍼레이션이 있는 것을 먼저 단언한다 — 경로가 사라지면 집합 비교가 공짜로 통과한다.
        JsonNode categories = paths.path("/api/v1/categories").path("get");
        assertThat(categories.isObject()).isTrue();
        assertThat(categories.has("x-error-codes")).isFalse();

        List<String> keyed = paths.properties().stream()
                .flatMap(path -> path.getValue().properties().stream()
                        .filter(operation -> operation.getValue().has("x-error-codes"))
                        .map(operation -> operation.getKey() + " " + path.getKey()))
                .toList();
        assertThat(keyed)
                .containsExactlyInAnyOrder(
                        "get /api/v1/budgets",
                        "put /api/v1/budgets",
                        "delete /api/v1/budgets",
                        "get /api/v1/exchange-rates",
                        "get /api/v1/exchange-rates/range",
                        "get /api/v1/exchange-rates/snapshot",
                        "get /api/v1/exchange-rates/{currencyCode}",
                        "get /api/v1/exchange-rates/status");
    }

    // value(List) 는 JSONArray 를 기대값 타입(불변 List)으로 다시 매핑하다 null 이 된다 — 원시 값을 equalTo 로 비교한다.
    private static List<String> withCommon(String... domainCodes) {
        return Stream.concat(COMMON.stream(), Arrays.stream(domainCodes))
                .sorted()
                .toList();
    }

    private static String exchangeRateGetCodes(String subPath) {
        return "$.paths['/api/v1/exchange-rates" + subPath + "'].get['x-error-codes']";
    }

    @TestConfiguration
    static class TestConfig {

        @Bean
        @ServiceConnection
        PostgreSQLContainer postgresContainer() {
            return new PostgreSQLContainer("postgres:16-alpine").withInitScript("testcontainers/auth-users-stub.sql");
        }
    }
}

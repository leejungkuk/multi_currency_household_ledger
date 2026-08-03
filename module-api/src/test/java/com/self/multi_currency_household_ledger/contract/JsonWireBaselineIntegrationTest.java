package com.self.multi_currency_household_ledger.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.self.multi_currency_household_ledger.AuthUserFixture;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 응답 <b>원문 바디</b>와 요청 본문 관용성을 3.4.1 기준선으로 고정한다.
 *
 * <p>기존 단언은 전부 이 축에 눈이 멀어 있다. {@code jsonPath().value(1300.00)} 은 Double 로 변환해 비교하고,
 * {@code isEqualByComparingTo} 는 계약상 scale 을 무시하며, {@code readTree(...).decimalValue()} 는 Jackson 이
 * 어느 모드든 {@code 19.50} 을 {@code 19.5} 로 되돌려준다. 그래서 여기서는 <b>{@code getContentAsString()} 원문
 * 문자열</b>을 골든 파일과 비교한다 — scale·날짜 포맷·필드 순서·null 포함 여부·비 BMP 문자 이스케이프가 한 단언에
 * 전부 걸린다. {@code JsonNode} 로 되돌리지 마라.
 *
 * <p>배치가 {@code module-api} 인 것이 요건이다. {@code spring.jackson.*} 은 {@code module-api} 의
 * {@code application.yml} 에만 있고 {@code module-ledger} 는 이 모듈을 의존하지 않는다 — 거기 슬라이스로 두면
 * 플래그가 안 걸린 {@code ObjectMapper} 로 돌아 항상 통과하면서 프로덕션만 회귀한다.
 *
 * <p>골든이 깨지면 <b>골든을 갱신하지 말고 원인을 규명한다.</b> 갱신하는 순간 게이트가 사라진다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@TestPropertySource(
        properties = {
            "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://example.supabase.co/auth/v1",
            "exchange.eximbank.api-key=test-api-key"
        })
class JsonWireBaselineIntegrationTest {

    private static final UUID MEMBER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    /**
     * {@code ApiResponse}·{@code ErrorResponse} 는 주입 {@code Clock} 이 아니라 {@code LocalDateTime.now(KST)} 를
     * 직접 부르므로 고정할 수 없다. 자릿수 마스킹은 {@code ISO_LOCAL_DATE_TIME} 의 소수부 길이가 0/3/6/9 로
     * 흔들려 flaky 하고, {@code "[^"]*"} 치환은 포맷 변화를 통째로 삼킨다. ISO 구조를 강제하는 앵커 정규식이라
     * 포맷이 바뀌면(배열·epoch·오프셋 부착) 매치가 안 돼 원문이 남고 골든이 깨진다.
     */
    private static final Pattern TIMESTAMP =
            Pattern.compile("\"timestamp\":\"\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,9})?\"");

    private static final String CREATE_USD_EXPENSE =
            """
            {"amount":19.50,"currencyCode":"USD","categoryId":1,"assetId":3,"transactionDate":"2026-04-06"}""";

    private static final String CREATE_KRW_INCOME =
            """
            {"amount":30000.00,"currencyCode":"KRW","categoryId":14,"assetId":1,\
            "transactionDate":"2026-04-07","memo":"4월 급여"}""";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    @SuppressWarnings("UnusedVariable")
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void setUp() {
        AuthUserFixture authUsers = new AuthUserFixture(jdbcTemplate);
        authUsers.reset(MEMBER_ID);
        jdbcTemplate.update("delete from exchange_rate");
        // id 를 정규식으로 마스킹하는 대신 identity 를 되감아 골든이 실제 id 를 그대로 담게 한다.
        // 마스킹은 봉투의 자동 생성 id 와 시드 카탈로그의 고정 id 를 구분하지 못해 후자의 검증까지 지운다.
        jdbcTemplate.execute("alter table ledger_entry alter column id restart with 1");
        jdbcTemplate.update(
                """
                insert into exchange_rate (currency_code, tts, base_date, created_at, updated_at)
                values ('USD', 1300.500000, date '2026-04-03', timestamp '2026-04-03T11:05:00',
                        timestamp '2026-04-03T11:05:00'),
                       ('JPY', 950.120000, date '2026-04-03', timestamp '2026-04-03T11:05:30.123456',
                        timestamp '2026-04-03T11:05:30.123456')
                """);
    }

    @Test
    @DisplayName("외화 거래 생성 응답 원문은 scale 2·scale 6·중첩 객체·null memo 까지 골든과 일치한다")
    void create_foreign_ledger_entry_response_matches_golden() throws Exception {
        assertGolden(
                "ledger-create-foreign.json", createEntry(CREATE_USD_EXPENSE).andExpect(status().isOk()));
    }

    @Test
    @DisplayName("월 합계 응답 원문은 mock 없는 실 DB 집계 sum() 의 scale 까지 골든과 일치한다")
    void monthly_summary_response_matches_golden() throws Exception {
        seedMonthlyEntries();

        assertGolden(
                "ledger-monthly-summary.json",
                monthly("/api/v1/ledgers/summary").andExpect(status().isOk()));
    }

    @Test
    @DisplayName("월 거래 목록 응답 원문은 배열 정렬과 DB 왕복 후의 환율 scale 까지 골든과 일치한다")
    void monthly_entries_response_matches_golden() throws Exception {
        seedMonthlyEntries();

        assertGolden("ledger-monthly-entries.json", monthly("/api/v1/ledgers").andExpect(status().isOk()));
    }

    @Test
    @DisplayName("환율 상태 응답 원문은 snake_case 필드명과 LocalDateTime 포맷까지 골든과 일치한다")
    void exchange_rate_status_response_matches_golden() throws Exception {
        assertGolden(
                "exchange-rate-status.json",
                mockMvc.perform(get("/api/v1/exchange-rates/status")).andExpect(status().isOk()));
    }

    @Test
    @DisplayName("무토큰 요청의 401 봉투 원문은 RestControllerAdvice 를 타지 않는 경로에서도 골든과 일치한다")
    void unauthorized_error_envelope_matches_golden() throws Exception {
        assertGolden(
                "unauthorized-error.json",
                mockMvc.perform(get("/api/v1/ledgers").param("year", "2026").param("month", "4"))
                        .andExpect(status().isUnauthorized()));
    }

    @Test
    @DisplayName("검증 실패 400 봉투 원문은 RestControllerAdvice 와 메시지 컨버터를 거치는 경로에서도 골든과 일치한다")
    void validation_error_envelope_matches_golden() throws Exception {
        assertGolden(
                "validation-error.json",
                createEntry(
                                """
                                {"currencyCode":"KRW","categoryId":1,"assetId":3,"transactionDate":"2026-04-06"}""")
                        .andExpect(status().isBadRequest()));
    }

    @Test
    @DisplayName("스키마 밖 필드가 섞인 거래 생성 요청은 그 필드를 무시하고 200 으로 저장한다")
    void unknown_request_field_is_ignored() throws Exception {
        String body = createEntry(
                        """
                        {"amount":1000.00,"currencyCode":"KRW","categoryId":1,"assetId":3,\
                        "transactionDate":"2026-04-06","unknownField":"x"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.currencyCode").value("KRW"))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        // KRW 는 도메인이 BigDecimal.ONE(scale 0)을 넣으므로 생성 응답 wire 가 "1" 이다. DB 왕복 후의
        // "1.000000"(ledger-monthly-entries.json)과 값이 달라 골든 어느 것도 이 지점을 덮지 못한다.
        assertThat(body).contains("\"appliedRate\":1,");
    }

    private void seedMonthlyEntries() throws Exception {
        createEntry(CREATE_USD_EXPENSE).andExpect(status().isOk());
        createEntry(CREATE_KRW_INCOME).andExpect(status().isOk());
    }

    private ResultActions createEntry(String body) throws Exception {
        return mockMvc.perform(post("/api/v1/ledgers")
                .with(token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions monthly(String path) throws Exception {
        return mockMvc.perform(withMonth(get(path)));
    }

    private static MockHttpServletRequestBuilder withMonth(MockHttpServletRequestBuilder request) {
        return request.param("year", "2026").param("month", "4").with(token());
    }

    private static void assertGolden(String goldenName, ResultActions result) throws Exception {
        String body = result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(TIMESTAMP.matcher(body).replaceAll("\"timestamp\":\"<TS>\""))
                .isEqualTo(new ClassPathResource("golden/%s".formatted(goldenName))
                        .getContentAsString(StandardCharsets.UTF_8)
                        .strip());
    }

    private static RequestPostProcessor token() {
        return jwt().jwt(t -> t.subject(MEMBER_ID.toString()).audience(List.of("authenticated")));
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

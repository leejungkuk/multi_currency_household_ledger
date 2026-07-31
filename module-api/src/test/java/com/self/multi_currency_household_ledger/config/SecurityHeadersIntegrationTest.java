package com.self.multi_currency_household_ledger.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.self.multi_currency_household_ledger.common.web.CacheControlHeaders;
import java.net.URI;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 응답 보안 헤더와 permitAll 정규식 매처의 <b>런타임 거동</b>을 값까지 고정한다.
 *
 * <p>둘 다 지금까지 단언이 0 건이었다. 보안 헤더는 Spring Security 의 기본 라이터 집합이 정하고,
 * {@code {currencyCode:[A-Z]{3}}} 의 매칭 의미는 매처 구현이 정한다 — 둘 다 코드가 아니라 <b>프레임워크 버전</b>이
 * 바꾸는 값이다. {@link PermitAllSnapshotTest} 는 매처의 문자열 표현만 비교하므로 매칭 의미가 달라져도 통과한다.
 *
 * <p>기대값은 "무엇이 옳은가"가 아니라 <b>현 거동의 박제</b>다. 업그레이드 후 깨지면 기대값을 고치지 말고 원인을
 * 규명한다 — 특히 {@code /usd}·{@code /ABCD} 가 401 이 아니게 되면 통화코드 제약이 사라져 한 세그먼트 와일드카드가
 * 된 것이고, 그 순간 컨트롤러에 GET 하나를 더 붙이는 것만으로 무인증 공개된다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@TestPropertySource(
        properties = {
            "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://example.supabase.co/auth/v1",
            "exchange.eximbank.api-key=test-api-key"
        })
class SecurityHeadersIntegrationTest {

    /** Security 의 {@code CacheControlHeadersWriter} 기본값 — 금융 데이터가 캐시에 남지 않게 하는 축. */
    private static final String NO_STORE_CACHE_CONTROL = "no-cache, no-store, max-age=0, must-revalidate";

    /** Security 의 {@code HstsHeaderWriter} 기본값. {@code " ; "} 구분자의 공백까지 프레임워크가 정한다. */
    private static final String HSTS = "max-age=31536000 ; includeSubDomains";

    /**
     * 대표 응답의 헤더 이름 집합. 값 고정만으로는 Security 가 기본 라이터를 <b>추가</b>했을 때 전부 그린이다 —
     * 새 헤더는 클라이언트 거동을 바꾸므로 무해한 방향이 아니다. 인증 통과 응답을 대표로 쓰는 이유는 공개 조회와 달리
     * 컨트롤러가 {@code Cache-Control} 을 덮지 않아 Security 기본 세트가 온전히 실리기 때문이다.
     */
    private static final List<String> EXPECTED_HEADER_NAMES = List.of(
            "Vary",
            "Content-Type",
            "X-Content-Type-Options",
            "X-XSS-Protection",
            "Cache-Control",
            "Pragma",
            "Expires",
            "X-Frame-Options");

    /** HSTS 는 secure 요청에서만 나가므로 평문 세트와 집합이 다르다 — "기본 세트 + HSTS 하나"가 불변식이다. */
    private static final List<String> EXPECTED_SECURE_HEADER_NAMES = Stream.concat(
                    EXPECTED_HEADER_NAMES.stream(), Stream.of("Strict-Transport-Security"))
            .toList();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    @SuppressWarnings("UnusedVariable")
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("delete from exchange_rate");
        jdbcTemplate.update(
                """
                insert into exchange_rate (currency_code, tts, base_date, created_at, updated_at)
                values ('USD', 1300.500000, date '2026-04-03', timestamp '2026-04-03T11:05:00',
                        timestamp '2026-04-03T11:05:00')
                """);
    }

    @Test
    @DisplayName("공개 조회 응답은 컨트롤러가 실은 public 캐시 헤더를 유지하고 Security 의 no-store 세트로 덮이지 않는다")
    void public_read_responses_keep_controller_cache_control() throws Exception {
        assertCommonSecurityHeaders(
                        mockMvc.perform(get("/api/v1/exchange-rates").param("date", "2026-04-03")))
                .andExpect(header().string("Cache-Control", CacheControlHeaders.PUBLIC_READ))
                // CacheControlHeadersWriter 는 Cache-Control 이 이미 있으면 세 헤더를 통째로 건너뛴다.
                // Pragma·Expires 가 생기면 그 규칙이 바뀐 것이고 공개 캐시가 무력화된다.
                .andExpect(header().doesNotExist("Pragma"))
                .andExpect(header().doesNotExist("Expires"));

        assertCommonSecurityHeaders(mockMvc.perform(get("/api/v1/categories").param("transactionType", "EXPENSE")))
                .andExpect(header().string("Cache-Control", CacheControlHeaders.PUBLIC_READ));

        assertCommonSecurityHeaders(mockMvc.perform(get("/api/v1/assets")))
                .andExpect(header().string("Cache-Control", CacheControlHeaders.PUBLIC_READ));
    }

    @Test
    @DisplayName("인증이 필요한 요청의 401 응답에는 Security 기본 헤더 세트가 그대로 실린다")
    void unauthorized_response_carries_default_security_headers() throws Exception {
        assertCommonSecurityHeaders(monthlyLedgers(null))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Cache-Control", NO_STORE_CACHE_CONTROL))
                .andExpect(header().string("Pragma", "no-cache"))
                .andExpect(header().string("Expires", "0"));
    }

    @Test
    @DisplayName("인증을 통과한 거래 조회 응답은 no-store 를 포함해 금융 데이터가 캐시에 남지 않게 한다")
    void authenticated_ledger_response_is_not_cacheable() throws Exception {
        assertCommonSecurityHeaders(monthlyLedgers(token()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", NO_STORE_CACHE_CONTROL))
                .andExpect(header().string("Pragma", "no-cache"))
                .andExpect(header().string("Expires", "0"))
                // Vary 는 CORS 처리기가 값 3 개를 개별 헤더로 붙인다. string() 은 첫 값만 보므로 2·3 번째 값이
                // 사라지거나 4 번째 값이 붙어도 통과한다 — 목록 전체를 고정하려면 stringValues 가 필요하다.
                .andExpect(header().stringValues(
                                "Vary", "Origin", "Access-Control-Request-Method", "Access-Control-Request-Headers"))
                .andExpect(header().string("Content-Type", "application/json"));
    }

    @Test
    @DisplayName("인증 통과 응답의 헤더 이름 집합이 스냅샷과 정확히 일치한다")
    void authenticated_response_header_names_match_snapshot() throws Exception {
        var response =
                monthlyLedgers(token()).andExpect(status().isOk()).andReturn().getResponse();

        assertThat(response.getHeaderNames()).containsExactlyInAnyOrderElementsOf(EXPECTED_HEADER_NAMES);
    }

    @Test
    @DisplayName("HTTPS 요청 응답에는 HSTS 가 Security 기본값 그대로 실린다")
    void secure_request_response_carries_hsts() throws Exception {
        assertCommonSecurityHeaders(monthlyLedgersOverHttps())
                .andExpect(status().isOk())
                .andExpect(header().string("Strict-Transport-Security", HSTS));
    }

    @Test
    @DisplayName("HTTPS 요청 응답의 헤더 이름 집합은 기본 세트에 HSTS 하나가 더해진 스냅샷과 일치한다")
    void secure_response_header_names_match_snapshot() throws Exception {
        var response =
                monthlyLedgersOverHttps().andExpect(status().isOk()).andReturn().getResponse();

        assertThat(response.getHeaderNames()).containsExactlyInAnyOrderElementsOf(EXPECTED_SECURE_HEADER_NAMES);
    }

    @Test
    @DisplayName("permitAll 의 통화코드 정규식은 대문자 3자리만 공개하고 그 밖의 세그먼트는 401 로 막는다")
    void currency_code_regex_matcher_opens_only_three_upper_case_letters() throws Exception {
        mockMvc.perform(get("/api/v1/exchange-rates/USD")).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/exchange-rates/usd")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/exchange-rates/ABCD")).andExpect(status().isUnauthorized());
        // 퍼센트 인코딩(%55 = 'U')은 매칭 전에 디코딩되므로 /USD 와 같은 요청으로 취급된다.
        mockMvc.perform(get(URI.create("/api/v1/exchange-rates/%55SD"))).andExpect(status().isOk());
        // 트레일링 슬래시는 패턴과 매치되지 않아 permitAll 에서 빠진다.
        mockMvc.perform(get("/api/v1/exchange-rates/USD/")).andExpect(status().isUnauthorized());
    }

    private ResultActions monthlyLedgers(RequestPostProcessor postProcessor) throws Exception {
        var request = get("/api/v1/ledgers").param("year", "2026").param("month", "4");
        return mockMvc.perform(postProcessor == null ? request : request.with(postProcessor));
    }

    /**
     * {@code HstsHeaderWriter} 는 {@code request.isSecure()} 에서만 동작한다. 운영에서는 Caddy 의
     * {@code X-Forwarded-Proto: https} 를 {@code forward-headers-strategy: native}(Tomcat {@code RemoteIpValve})가
     * {@code setSecure(true)} 로 옮겨 실제로 실리지만, 그 밸브는 MockMvc 에 없어 헤더만 붙이면 HSTS 가 나오지
     * 않는다(실측). 그래서 {@code .secure(true)} 가 이 경로를 재현하는 유일한 레버다.
     */
    private ResultActions monthlyLedgersOverHttps() throws Exception {
        return mockMvc.perform(get("/api/v1/ledgers")
                .param("year", "2026")
                .param("month", "4")
                .secure(true)
                .with(token()));
    }

    /** 경로와 무관하게 항상 실려야 하는 헤더. 이름 단위로 값까지 고정한다. */
    private static ResultActions assertCommonSecurityHeaders(ResultActions result) throws Exception {
        return result.andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("X-XSS-Protection", "0"));
    }

    private static RequestPostProcessor token() {
        return jwt().jwt(t -> t.subject("00000000-0000-0000-0000-000000000001").audience(List.of("authenticated")));
    }

    @TestConfiguration
    static class TestConfig {

        @Bean
        @ServiceConnection
        PostgreSQLContainer<?> postgresContainer() {
            return new PostgreSQLContainer<>("postgres:16-alpine");
        }
    }
}

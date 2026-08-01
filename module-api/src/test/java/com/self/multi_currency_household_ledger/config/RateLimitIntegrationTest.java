package com.self.multi_currency_household_ledger.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.Filter;
import jakarta.servlet.ServletContext;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.filter.CorsFilter;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(
        properties = {
            "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://example.supabase.co/auth/v1",
            "exchange.eximbank.api-key=test-api-key",
            "management.server.port=0",
            "woni.security.rate-limit.read-limit=2",
            "woni.security.rate-limit.write-limit=2"
        })
class RateLimitIntegrationTest {

    private static final String ALLOWED_ORIGIN = "http://localhost:3000";

    @LocalServerPort
    private int port;

    @Autowired
    private ServletContext servletContext;

    @Autowired
    private FilterChainProxy filterChainProxy;

    @Autowired
    private RateLimitFilter rateLimitFilter;

    @MockitoBean
    @SuppressWarnings("UnusedVariable")
    private JwtDecoder jwtDecoder;

    @Test
    @DisplayName("진짜 CORS 프리플라이트는 쓰기 한도를 소모하지 않는다")
    void preflight_does_not_consume_write_limit() throws Exception {
        String ip = "203.0.113.71";
        for (int request = 0; request < 3; request++) {
            assertThat(preflight("/api/v1/ledgers/sync", ip).statusCode()).isEqualTo(200);
        }

        assertThat(post("/api/v1/ledgers/sync", ip).statusCode()).isEqualTo(401);
        assertThat(post("/api/v1/ledgers/sync", ip).statusCode()).isEqualTo(401);
        assertThat(post("/api/v1/ledgers/sync", ip).statusCode()).isEqualTo(429);
    }

    @Test
    @DisplayName("CORS 요청의 429 응답에도 허용 오리진 헤더가 있다")
    void rejected_cors_request_keeps_allow_origin_header() throws Exception {
        String ip = "203.0.113.72";
        assertThat(get("/api/v1/assets", ip, ALLOWED_ORIGIN, null).statusCode()).isEqualTo(200);
        assertThat(get("/api/v1/assets", ip, ALLOWED_ORIGIN, null).statusCode()).isEqualTo(200);

        HttpResponse<Void> rejected = get("/api/v1/assets", ip, ALLOWED_ORIGIN, null);

        assertThat(rejected.statusCode()).isEqualTo(429);
        assertThat(rejected.headers().firstValue("Access-Control-Allow-Origin")).contains(ALLOWED_ORIGIN);
        // 실 Tomcat + server.compression 을 통과한 429 는 여기서만 관측된다 — 재시도 헤더가 살아 나오는지 함께 고정한다.
        assertThat(rejected.headers().firstValue("Retry-After")).isPresent();
    }

    @Test
    @DisplayName("깨진 Bearer 토큰 반복은 인증 전에 429로 전환된다")
    void invalid_bearer_token_is_rate_limited_before_authentication() throws Exception {
        String ip = "203.0.113.73";
        assertThat(get("/api/v1/ledgers", ip, null, "Bearer broken").statusCode())
                .isEqualTo(401);
        assertThat(get("/api/v1/ledgers", ip, null, "Bearer broken").statusCode())
                .isEqualTo(401);

        assertThat(get("/api/v1/ledgers", ip, null, "Bearer broken").statusCode())
                .isEqualTo(429);
    }

    @Test
    @DisplayName("필터는 컨테이너 자동 등록 없이 CORS 뒤 인증 앞에서 정확한 한도만 통과시킨다")
    void filter_is_registered_only_in_security_chain_at_the_expected_position() throws Exception {
        assertThat(servletContext.getFilterRegistrations().values())
                .noneMatch(registration -> RateLimitFilter.class.getName().equals(registration.getClassName()));

        List<Filter> filters = filterChainProxy.getFilters("/api/v1/assets");
        int corsIndex = indexOf(filters, CorsFilter.class);
        int rateLimitIndex = filters.indexOf(rateLimitFilter);
        int bearerIndex = indexOf(filters, BearerTokenAuthenticationFilter.class);
        assertThat(corsIndex).isNotNegative();
        assertThat(rateLimitIndex).isNotNegative();
        assertThat(bearerIndex).isNotNegative();
        assertThat(rateLimitIndex).isGreaterThan(corsIndex).isLessThan(bearerIndex);

        String ip = "203.0.113.74";
        assertThat(get("/api/v1/assets", ip, null, null).statusCode()).isEqualTo(200);
        assertThat(get("/api/v1/assets", ip, null, null).statusCode()).isEqualTo(200);
        assertThat(get("/api/v1/assets", ip, null, null).statusCode()).isEqualTo(429);
    }

    private HttpResponse<Void> preflight(String path, String ip) throws Exception {
        HttpRequest request = request(path, ip)
                .header("Origin", ALLOWED_ORIGIN)
                .header("Access-Control-Request-Method", "POST")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .build();
        return send(request);
    }

    private HttpResponse<Void> post(String path, String ip) throws Exception {
        return send(request(path, ip).POST(HttpRequest.BodyPublishers.noBody()).build());
    }

    private HttpResponse<Void> get(String path, String ip, String origin, String authorization) throws Exception {
        HttpRequest.Builder request = request(path, ip);
        if (origin != null) {
            request.header("Origin", origin);
        }
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        return send(request.GET().build());
    }

    private HttpRequest.Builder request(String path, String ip) {
        return HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:%d%s".formatted(port, path)))
                .header("X-Forwarded-For", ip);
    }

    private static HttpResponse<Void> send(HttpRequest request) throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request, HttpResponse.BodyHandlers.discarding());
        }
    }

    private static int indexOf(List<Filter> filters, Class<? extends Filter> type) {
        for (int index = 0; index < filters.size(); index++) {
            if (type.isInstance(filters.get(index))) {
                return index;
            }
        }
        return -1;
    }

    @TestConfiguration
    static class TestConfig {

        @Bean
        @ServiceConnection
        PostgreSQLContainer postgresContainer() {
            return new PostgreSQLContainer("postgres:16-alpine");
        }

        /**
         * 운영 {@code Clock.system} 을 쓰면 한 테스트의 연속 요청 사이에 60초 윈도우 경계가 끼어 카운터가 리셋되고, 429 를 기대한 마지막 요청이
         * 200/401 로 돌아온다. 낮은 확률이지만 실행 시각에만 의존하는 플래키다. 시계를 고정하면 세대가 교체되지 않아 결정적이 된다 — 윈도우 복구
         * 자체는 단위 테스트가 이미 검증하므로 여기서 잃는 커버리지는 없다.
         */
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.now(), ZoneId.of("Asia/Seoul"));
        }
    }
}

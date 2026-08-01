package com.self.multi_currency_household_ledger.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.registry.HealthContributorRegistry;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * actuator 는 애플리케이션 포트가 아니라 내부 전용 management 포트에서만 서비스된다. prometheus 스크랩 본문에는 JVM 상태·엔드포인트별 URI·DB 풀
 * 수치가 실리므로 공개 포트로 새면 그대로 정보 노출이다. 그래서 포트 격리를 테스트로 고정한다.
 *
 * <p>이전 주석은 "management context 가 부모의 security filter chain 을 물려받지 않는다"고 적었으나 그건 사실이 아니다 —
 * {@code ServletManagementChildContextConfiguration$ServletManagementContextSecurityConfiguration} 이 부모 빈팩토리에서
 * {@code springSecurityFilterChain} 을 꺼내 자식 컨텍스트에 재등록한다(바이트코드 확인). 즉 management 포트도 같은 체인을
 * 타므로 레이트 리밋도 함께 받는다. 포트 격리가 유일한 방어선인 것은 맞지만 그 이유가 체인 미상속이어서는 아니다.
 */
// Spring Boot 는 테스트에서 metrics export 를 기본으로 끈다(management.defaults.metrics.export.enabled=false).
// 이 어노테이션이 없으면 PrometheusMeterRegistry 빈 자체가 만들어지지 않아 /actuator/prometheus 가 아예 없는
// 경로가 된다 — 운영 설정과는 무관한 테스트 전용 함정이라 여기서만 되살린다.
@AutoConfigureMetrics
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(
        properties = {
            "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://example.supabase.co/auth/v1",
            "exchange.eximbank.api-key=test-api-key",
            // 운영은 9091 고정이지만 테스트는 병렬 실행 충돌을 피해 랜덤 포트를 받는다.
            "management.server.port=0"
        })
class ActuatorEndpointIntegrationTest {

    @LocalServerPort
    private int applicationPort;

    @LocalManagementPort
    private int managementPort;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private HealthContributorRegistry healthContributorRegistry;

    @Autowired
    private ObjectProvider<RateLimitFilter> rateLimitFilterProvider;

    @MockitoBean
    @SuppressWarnings("UnusedVariable")
    private JwtDecoder jwtDecoder;

    @Test
    @DisplayName("management 포트의 GET /actuator/health 는 토큰 없이 200 UP 을 반환한다")
    void health_without_token_returns_up() throws Exception {
        HttpResponse<String> response = get(managementPort, "/actuator/health");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(response.body()).path("status").asString())
                .isEqualTo("UP");
    }

    /**
     * 레이트 리밋이 운영 기본값에서 실제로 켜지는지 고정한다. 속성 오버라이드가 없는 이 컨텍스트에 얹어 Testcontainers 컨텍스트를 하나도 늘리지 않는다.
     * 다른 테스트 본문에 끼워 넣지 않는 이유는, 그 테스트가 수정·교체될 때 이 회귀 가드가 조용히 사라지기 때문이다.
     */
    @Test
    @DisplayName("속성 오버라이드가 없으면 레이트 리밋 필터 빈이 존재한다")
    void rate_limit_filter_is_enabled_by_default() {
        assertThat(rateLimitFilterProvider.getIfAvailable()).isNotNull();
    }

    @Test
    @DisplayName("health 응답은 status 외 상세 정보를 노출하지 않는다")
    void health_does_not_expose_details() throws Exception {
        JsonNode body =
                objectMapper.readTree(get(managementPort, "/actuator/health").body());

        assertThat(body.has("components")).isFalse();
        assertThat(body.has("details")).isFalse();
        assertThat(body.properties()).extracting(java.util.Map.Entry::getKey).containsExactly("status");
    }

    @Test
    @DisplayName("DB 연결 상태가 health 판정에 포함된다")
    void health_includes_database_check() {
        assertThat(healthContributorRegistry.getContributor("db")).isNotNull();
    }

    @Test
    @DisplayName("management 포트의 GET /actuator/prometheus 는 토큰 없이 스크랩 가능한 메트릭을 반환한다")
    void prometheus_scrape_without_token_returns_metrics() throws Exception {
        // 스크랩 전에 실제 요청을 한 번 흘려야 http.server.requests 시계열이 생긴다.
        get(applicationPort, "/api/v1/categories");

        HttpResponse<String> response = get(managementPort, "/actuator/prometheus");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .contains("jvm_memory_used_bytes")
                .contains("hikaricp_connections_active")
                // p95/p99 를 PromQL 로 계산하려면 버킷 시계열이 있어야 한다.
                .contains("http_server_requests_seconds_bucket")
                // 여러 인스턴스·환경의 시계열을 Grafana 에서 구분하는 공통 라벨.
                .contains("application=\"woni-api\"");
    }

    @Test
    @DisplayName("애플리케이션 포트에는 actuator 가 서비스되지 않는다")
    void application_port_does_not_serve_actuator() throws Exception {
        assertThat(get(applicationPort, "/actuator/health").statusCode()).isNotEqualTo(200);
        assertThat(get(applicationPort, "/actuator/prometheus").statusCode()).isNotEqualTo(200);
    }

    @Test
    @DisplayName("health·prometheus 외 다른 actuator 엔드포인트는 노출되지 않는다")
    void other_actuator_endpoints_are_not_exposed() throws Exception {
        assertThat(get(managementPort, "/actuator/env").statusCode()).isNotEqualTo(200);
        assertThat(get(managementPort, "/actuator/beans").statusCode()).isNotEqualTo(200);
        assertThat(get(managementPort, "/actuator/heapdump").statusCode()).isNotEqualTo(200);
    }

    private HttpResponse<String> get(int port, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:%d%s".formatted(port, path)))
                .GET()
                .build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    @TestConfiguration
    static class TestConfig {

        @Bean
        @ServiceConnection
        PostgreSQLContainer postgresContainer() {
            return new PostgreSQLContainer("postgres:16-alpine");
        }
    }
}

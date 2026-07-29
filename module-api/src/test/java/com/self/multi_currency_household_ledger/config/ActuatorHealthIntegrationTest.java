package com.self.multi_currency_household_ledger.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.HealthContributorRegistry;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 배포 플랫폼·컨테이너 재시작 판정이 이 엔드포인트에 의존하므로 무토큰 접근과 응답 형태를 고정한다. 상세 정보는 노출하지 않는다(show-details: never) —
 * 공개 엔드포인트라 DB 종류·디스크 용량 등 내부 정보가 새면 안 된다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(
        properties = {
            "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://example.supabase.co/auth/v1",
            "exchange.eximbank.api-key=test-api-key"
        })
class ActuatorHealthIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private HealthContributorRegistry healthContributorRegistry;

    @MockitoBean
    @SuppressWarnings("UnusedVariable")
    private JwtDecoder jwtDecoder;

    @Test
    @DisplayName("GET /actuator/health 는 토큰 없이 200 UP 을 반환한다")
    void health_without_token_returns_up() throws Exception {
        HttpResponse<String> response = get("/actuator/health");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(response.body()).path("status").asText())
                .isEqualTo("UP");
    }

    @Test
    @DisplayName("health 응답은 status 외 상세 정보를 노출하지 않는다")
    void health_does_not_expose_details() throws Exception {
        JsonNode body = objectMapper.readTree(get("/actuator/health").body());

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
    @DisplayName("health 외 다른 actuator 엔드포인트는 노출되지 않는다")
    void other_actuator_endpoints_are_not_exposed() throws Exception {
        assertThat(get("/actuator/env").statusCode()).isNotEqualTo(200);
        assertThat(get("/actuator/beans").statusCode()).isNotEqualTo(200);
    }

    private HttpResponse<String> get(String path) throws Exception {
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
        PostgreSQLContainer<?> postgresContainer() {
            return new PostgreSQLContainer<>("postgres:16-alpine");
        }
    }
}

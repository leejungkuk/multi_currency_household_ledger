package com.self.multi_currency_household_ledger.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroup;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroups;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 두 색 배포에서 Caddy 는 management 포트의 {@code /actuator/health/readiness} 를 토큰 없이 읽어 색별 투입을 정하고, 배포 에이전트는 컨테이너 안
 * 게이트 파일 하나로 그 응답을 여닫는다. 실제 두 포트로 띄워 그 경로가 파일을 따라가는지, 그리고 permitAll 이 그 한 경로보다 넓어지지 않았는지 고정한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(
        properties = {
            "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://example.supabase.co/auth/v1",
            "exchange.eximbank.api-key=test-api-key",
            // 운영은 9091 고정이지만 테스트는 병렬 실행 충돌을 피해 랜덤 포트를 받는다.
            "management.server.port=0"
        })
class DeployGateHealthIntegrationTest {

    @TempDir
    private static Path gateDir;

    @LocalServerPort
    private int applicationPort;

    @LocalManagementPort
    private int managementPort;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private HealthEndpointGroups healthEndpointGroups;

    @MockitoBean
    @SuppressWarnings("UnusedVariable")
    private JwtDecoder jwtDecoder;

    @DynamicPropertySource
    static void deployGateFile(DynamicPropertyRegistry registry) {
        registry.add("woni.deploy-gate.file", () -> gateFile().toString());
    }

    @BeforeEach
    void closeGate() throws Exception {
        Files.deleteIfExists(gateFile());
    }

    /**
     * 테스트 DB 는 늘 UP 이고 readinessState 는 늘 ACCEPTING 이라 HTTP 응답만으로는 그룹 구성이 바뀌어도 모른다. DB 를 넣으면 두 색이 같은 DB 를 쓰므로
     * DB 가 느려질 때 두 색이 동시에 회전에서 빠진다 — 그래서 구성 자체를 고정한다.
     */
    @Test
    @DisplayName("readiness 그룹은 readinessState 와 deployGate 만 담는다")
    void readiness_group_contains_only_readiness_state_and_deploy_gate() {
        HealthEndpointGroup readiness = healthEndpointGroups.get("readiness");

        assertThat(readiness).isNotNull();
        assertThat(readiness.isMember("readinessState")).isTrue();
        assertThat(readiness.isMember("deployGate")).isTrue();
        assertThat(readiness.isMember("db")).isFalse();
        assertThat(readiness.isMember("diskSpace")).isFalse();
        assertThat(readiness.isMember("ping")).isFalse();
        assertThat(readiness.isMember("livenessState")).isFalse();
    }

    @Test
    @DisplayName("management 포트의 무토큰 readiness 는 게이트 파일을 따라 503 OUT_OF_SERVICE 와 200 UP 을 오간다")
    void readiness_without_token_follows_deploy_gate_file() throws Exception {
        assertStatus(get(managementPort, "/actuator/health/readiness"), 503, "OUT_OF_SERVICE");

        Files.createFile(gateFile());
        assertStatus(get(managementPort, "/actuator/health/readiness"), 200, "UP");

        Files.delete(gateFile());
        assertStatus(get(managementPort, "/actuator/health/readiness"), 503, "OUT_OF_SERVICE");
    }

    @Test
    @DisplayName("게이트가 닫혀 있으면 루트 health 는 503 OUT_OF_SERVICE 이고 status·groups 외 필드를 내지 않는다")
    void root_health_is_out_of_service_while_gate_is_closed() throws Exception {
        HttpResponse<String> response = get(managementPort, "/actuator/health");
        JsonNode body = objectMapper.readTree(response.body());

        assertStatus(response, 503, "OUT_OF_SERVICE");
        assertThat(body.has("components")).isFalse();
        assertThat(body.has("details")).isFalse();
        assertThat(body.properties())
                .extracting(java.util.Map.Entry::getKey)
                .containsExactlyInAnyOrder("status", "groups");
    }

    @Test
    @DisplayName("liveness 와 개별 지시자 경로는 토큰 없이 열리지 않는다")
    void liveness_and_component_paths_still_require_authentication() throws Exception {
        assertThat(get(managementPort, "/actuator/health/liveness").statusCode())
                .isEqualTo(401);
        assertThat(get(managementPort, "/actuator/health/db").statusCode()).isNotEqualTo(200);
    }

    @Test
    @DisplayName("애플리케이션 포트에는 readiness 가 서비스되지 않는다")
    void application_port_does_not_serve_readiness() throws Exception {
        Files.createFile(gateFile());

        assertThat(get(applicationPort, "/actuator/health/readiness").statusCode())
                .isNotEqualTo(200);
    }

    private static Path gateFile() {
        return gateDir.resolve("woni-deploy-gate-open");
    }

    private void assertStatus(HttpResponse<String> response, int httpStatus, String status) throws Exception {
        assertThat(response.statusCode()).isEqualTo(httpStatus);
        assertThat(objectMapper.readTree(response.body()).path("status").asString())
                .isEqualTo(status);
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
            return new PostgreSQLContainer("postgres:16-alpine").withInitScript("testcontainers/auth-users-stub.sql");
        }
    }
}

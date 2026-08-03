package com.self.multi_currency_household_ledger.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.MeterRegistry;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(
        properties = {
            "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://example.supabase.co/auth/v1",
            "exchange.eximbank.api-key=test-api-key",
            "management.server.port=0",
            "woni.security.max-request-body-size=1KB"
        })
class RequestSizeLimitIntegrationTest {

    private static final String CREDENTIAL = UUID.randomUUID().toString();
    private static final String MEMBER_ID = "61bd39b9-267c-4ce5-85af-3025425e4ee0";
    private static final String OVERSIZED_JSON = "{\"memo\":\"" + "x".repeat(2_048) + "\"}";

    @LocalServerPort
    private int port;

    @Autowired
    private MeterRegistry meterRegistry;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void stubJwtDecoder() {
        Instant now = Instant.now();
        Jwt jwt = Jwt.withTokenValue(CREDENTIAL)
                .header("alg", "RS256")
                .subject(MEMBER_ID)
                .issuer("https://example.supabase.co/auth/v1")
                .audience(List.of("authenticated"))
                .issuedAt(now.minusSeconds(60))
                .expiresAt(now.plusSeconds(300))
                .build();
        when(jwtDecoder.decode(CREDENTIAL)).thenReturn(jwt);
    }

    @Test
    @DisplayName("인증된 chunked JSON은 판독 중 상한을 넘으면 413이고 관측 메트릭에 집계된다")
    void authenticated_chunked_json_is_rejected_and_observed() throws Exception {
        long before = requestCount("413");
        HttpRequest request = request("/api/v1/ledgers/sync")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + CREDENTIAL)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .POST(HttpRequest.BodyPublishers.ofInputStream(
                        () -> new ByteArrayInputStream(OVERSIZED_JSON.getBytes(StandardCharsets.UTF_8))))
                .build();

        HttpResponse<String> response = send(request);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE.value());
        assertThat(response.body()).contains("\"code\":\"REQUEST_BODY_TOO_LARGE\"");
        awaitRequestCount("413", before + 1, "판독 경로 413도 전용 카운터 없이 http.server.requests{status=413}로 드러나야 한다");
    }

    @Test
    @DisplayName("Content-Length가 있는 인증 요청은 본문 판독 전에 413으로 거부되고 그 413도 관측된다")
    void authenticated_known_length_body_is_rejected_early() throws Exception {
        long before = requestCount("413");
        HttpRequest request = request("/api/v1/ledgers/sync")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + CREDENTIAL)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString(OVERSIZED_JSON))
                .build();

        assertThat(send(request).statusCode()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE.value());
        // 조기 거부는 체인을 타지 않고 필터가 직접 응답하므로, 필터가 관측 필터보다 앞에 등록되면 이 413은
        // http.server.requests에서 통째로 사라진다. order 하한(S6: 전용 카운터를 안 만드는 근거)을 실제로
        // 고정하는 것은 판독 경로(I1)가 아니라 이 단언이다 — 판독 경로는 DispatcherServlet 안에서 끝나
        // 필터 위치와 무관하게 집계된다.
        awaitRequestCount("413", before + 1, "조기 거부 413이 관측 필터 바깥으로 새면 S6의 '전용 카운터 불필요' 근거가 무너진다");
    }

    @Test
    @DisplayName("무인증 form-urlencoded chunked PUT도 Security 전에 413으로 거부된다")
    void unauthenticated_chunked_form_put_is_rejected_before_security() throws Exception {
        byte[] body = ("memo=" + "x".repeat(2_048)).getBytes(StandardCharsets.UTF_8);
        HttpRequest request = request("/api/v1/ledgers/1")
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_FORM_URLENCODED_VALUE)
                .PUT(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(body)))
                .build();

        HttpResponse<String> response = send(request);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE.value());
        assertThat(response.body()).contains("\"code\":\"REQUEST_BODY_TOO_LARGE\"");
    }

    @Test
    @DisplayName("상한 이하 무인증 요청은 크기 필터가 가로채지 않고 401을 유지한다")
    void small_unauthenticated_body_keeps_security_response() throws Exception {
        HttpRequest request = request("/api/v1/ledgers/sync")
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();

        assertThat(send(request).statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder().uri(URI.create("http://localhost:%d%s".formatted(port, path)));
    }

    private static HttpResponse<String> send(HttpRequest request) throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    /**
     * 응답 수신과 메트릭 기록 사이에는 동기화가 없다 — writeErrorResponse 의 writeValue 가 writer 를 close 하며
     * 응답을 커밋·플러시하고, http.server.requests 기록은 그 뒤 ServerHttpObservationFilter 의 finally 에서
     * 일어난다. 클라이언트 스레드가 먼저 깨면 delta 가 아직 0 이라 동기 단언은 드물게 플래키가 된다.
     */
    private void awaitRequestCount(String status, long expected, String reason) {
        await().atMost(Duration.ofSeconds(1))
                .untilAsserted(() -> assertThat(requestCount(status)).as(reason).isEqualTo(expected));
    }

    private long requestCount(String status) {
        return meterRegistry.find("http.server.requests").tag("status", status).timers().stream()
                .mapToLong(timer -> timer.count())
                .sum();
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

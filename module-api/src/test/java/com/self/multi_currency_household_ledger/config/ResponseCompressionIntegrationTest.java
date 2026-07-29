package com.self.multi_currency_household_ledger.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.self.multi_currency_household_ledger.exchange.domain.CurrencyCode;
import com.self.multi_currency_household_ledger.exchange.domain.ExchangeRate;
import com.self.multi_currency_household_ledger.exchange.domain.ExchangeRateRepository;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.web.server.Compression;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.util.unit.DataSize;
import org.testcontainers.containers.PostgreSQLContainer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(
        properties = {
            "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://example.supabase.co/auth/v1",
            "exchange.eximbank.api-key=test-api-key"
        })
class ResponseCompressionIntegrationTest {

    private static final int EXPECTED_RATE_COUNT = 40;
    private static final LocalDate FROM = LocalDate.of(2026, 4, 1);
    private static final LocalDate TO = LocalDate.of(2026, 4, 30);
    private static final List<CurrencyCode> CURRENCIES =
            List.of(CurrencyCode.USD, CurrencyCode.EUR, CurrencyCode.JPY, CurrencyCode.CNY);

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ExchangeRateRepository exchangeRateRepository;

    @Autowired
    private ServerProperties serverProperties;

    @MockitoBean
    @SuppressWarnings("UnusedVariable")
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void setUp() {
        exchangeRateRepository.deleteAll();
        List<ExchangeRate> rates = new ArrayList<>(EXPECTED_RATE_COUNT);
        for (int day = 1; day <= 10; day++) {
            for (CurrencyCode currencyCode : CURRENCIES) {
                rates.add(ExchangeRate.of(currencyCode, new BigDecimal("1300.123456"), LocalDate.of(2026, 4, day)));
            }
        }
        exchangeRateRepository.saveAll(rates);
    }

    @Test
    @DisplayName("큰 환율 범위 JSON 응답은 gzip으로 압축된다")
    void large_exchange_rate_range_response_is_gzip_compressed() throws Exception {
        HttpResponse<byte[]> response = get("/api/v1/exchange-rates/range?from=%s&to=%s".formatted(FROM, TO));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Encoding")).contains("gzip");

        byte[] decompressedBody = decompress(response.body());
        JsonNode responseJson = objectMapper.readTree(decompressedBody);

        assertThat(responseJson.path("success").asBoolean()).isTrue();
        assertThat(decompressedBody.length).isGreaterThan(2048);
        assertThat(responseJson.path("data").size()).isEqualTo(EXPECTED_RATE_COUNT);

        Compression compression = serverProperties.getCompression();
        assertThat(compression.getEnabled()).isTrue();
        assertThat(compression.getMimeTypes()).contains("application/json");
        assertThat(compression.getMinResponseSize()).isEqualTo(DataSize.ofKilobytes(2));
    }

    /**
     * min-response-size 는 이 앱에서 게이트로 동작하지 않는다. Tomcat CompressionConfig.useCompression() 은
     * Content-Length 가 -1 이면 크기 비교를 건너뛰는데, Spring MVC 의 JSON 은 chunked 로 나가 항상 -1 이다.
     * 즉 크기로는 걸러지지 않는다 — 다른 제외 조건에 걸리지 않는 한, gzip 을 수락하는 요청이면
     * 임계값 미만의 작은 응답도 /range 가 아닌 다른 엔드포인트도 압축된다. 실제 동작을 고정해 둔다.
     */
    @Test
    @DisplayName("임계값 미만인 단건 조회 응답도 압축된다 — Content-Length 미상이라 크기 게이트가 적용되지 않는다")
    void small_json_response_is_compressed_too() throws Exception {
        HttpResponse<byte[]> response = get("/api/v1/exchange-rates/USD?date=%s".formatted(FROM));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Encoding")).contains("gzip");
        assertThat(decompress(response.body()).length).isLessThan(2048);
    }

    private HttpResponse<byte[]> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:%d%s".formatted(port, path)))
                .header("Accept-Encoding", "gzip")
                .GET()
                .build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        }
    }

    private static byte[] decompress(byte[] body) throws Exception {
        try (GZIPInputStream gzipInputStream = new GZIPInputStream(new ByteArrayInputStream(body))) {
            return gzipInputStream.readAllBytes();
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

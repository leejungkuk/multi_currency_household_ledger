package com.self.multi_currency_household_ledger.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * API 계약 스냅샷 생성기. 전체 애플리케이션 컨텍스트를 Testcontainers Postgres 위에 부팅하고
 * springdoc 이 만든 {@code /v3/api-docs} 를 떠서 raw OpenAPI 스펙을 파일로 기록한다.
 *
 * <p>오프라인/CI 에서 동작하도록 (1) datasource 는 {@link ServiceConnection} 컨테이너로, (2) JWT 는 stub
 * {@link JwtDecoder} 로 대체해 기동 시 issuer OIDC 조회를 막고, (3) 보안이 deny-by-default 이므로 mock JWT 로
 * {@code /v3/api-docs} 에 접근한다. 정렬·포맷 정규화는 {@code tools/update-api-snapshot.sh}(json.tool --sort-keys)가
 * 담당하므로 여기서는 raw 응답만 기록한다.
 *
 * <p>일반 {@code test} 태스크에서는 제외되고({@code @Tag("snapshot")}), {@code :module-api:generateApiSnapshot}
 * 로만 실행된다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Import(OpenApiSnapshotTest.SnapshotTestConfig.class)
@Tag("snapshot")
@TestPropertySource(
        properties = {
            "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://example.supabase.co/auth/v1",
            "exchange.eximbank.api-key=test-api-key"
        })
class OpenApiSnapshotTest {

    private static final String DEFAULT_OUT = "build/openapi-raw.json";

    @Autowired
    private MockMvc mockMvc;

    /**
     * 실제 {@link JwtDecoder}(SecurityConfig)를 mock 으로 대체한다. 기동 시 issuer OIDC 조회를 막고,
     * 문서 생성은 토큰을 검증하지 않으므로(MockMvc 가 jwt() 로 인증 주입) 동작에 영향이 없다.
     */
    @MockitoBean
    @SuppressWarnings("UnusedVariable")
    private JwtDecoder jwtDecoder;

    @Test
    void writeOpenApiSpec() throws Exception {
        String spec = mockMvc.perform(get("/v3/api-docs").with(jwt()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(spec).contains("\"openapi\"").contains("\"paths\"");

        Path out = Path.of(System.getProperty("openapi.snapshot.out", DEFAULT_OUT));
        if (out.getParent() != null) {
            Files.createDirectories(out.getParent());
        }
        Files.writeString(out, spec, StandardCharsets.UTF_8);
    }

    @TestConfiguration
    static class SnapshotTestConfig {

        @Bean
        @ServiceConnection
        PostgreSQLContainer<?> postgresContainer() {
            return new PostgreSQLContainer<>("postgres:16-alpine");
        }
    }
}

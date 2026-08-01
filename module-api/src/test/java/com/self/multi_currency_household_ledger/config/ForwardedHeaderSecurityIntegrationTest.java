package com.self.multi_currency_household_ledger.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.Filter;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 프록시 전달 헤더의 <b>신뢰 경계</b>를 실제 Tomcat 으로 고정한다.
 *
 * <p>배경(Caddy v2.11.4 실측): {@code reverse_proxy} 는 클라이언트가 보낸 {@code X-Forwarded-For/Proto/Host} 를
 * 자기가 계산한 값으로 교체하지만, RFC 7239 {@code Forwarded} 헤더는 손대지 않고 그대로 넘긴다. Spring 의
 * {@code ForwardedHeaderFilter}(= {@code forward-headers-strategy: framework})는 {@code Forwarded} 를
 * {@code X-Forwarded-*} 보다 우선하므로, 그 조합이면 클라이언트가 자기 IP·스킴·호스트를 마음대로 주장할 수 있다.
 * 그래서 {@code native}(Tomcat {@code RemoteIpValve})를 쓴다 — {@code Forwarded} 를 아예 읽지 않는다.
 *
 * <p>이 구분이 중요한 이유는 접속 IP 를 근거로 하는 모든 판단(레이트 리밋·차단·감사 로그)이 위조 가능한 값 위에 서면
 * 방어가 아니라 착시가 되기 때문이다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(
        properties = {
            "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://example.supabase.co/auth/v1",
            "exchange.eximbank.api-key=test-api-key",
            "management.server.port=0"
        })
class ForwardedHeaderSecurityIntegrationTest {

    private static final String FORGED_IP = "9.9.9.9";
    private static final String PROXIED_IP = "203.0.113.7";

    @LocalServerPort
    private int port;

    @MockitoBean
    @SuppressWarnings("UnusedVariable")
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void resetCapture() {
        RemoteAddressCapture.OBSERVED.clear();
    }

    @Test
    @DisplayName("application.yml 은 Forwarded 를 읽지 않는 native 전략을 쓴다")
    void application_yml_uses_native_strategy() throws IOException {
        List<PropertySource<?>> sources =
                new YamlPropertySourceLoader().load("application.yml", new ClassPathResource("application.yml"));

        assertThat(sources.getFirst().getProperty("server.forward-headers-strategy"))
                .as("framework 로 바꾸면 Caddy 가 걸러주지 않는 Forwarded 헤더가 X-Forwarded-* 를 눌러 IP 위조가 열린다")
                .isEqualTo("native");
    }

    @Test
    @DisplayName("클라이언트가 보낸 Forwarded 헤더는 remote address 를 바꾸지 못한다")
    void forged_rfc7239_forwarded_header_is_ignored() throws Exception {
        get("Forwarded", "for=" + FORGED_IP + ";proto=https;host=evil.example");

        assertThat(RemoteAddressCapture.OBSERVED)
                .as("Caddy 가 통과시키는 헤더라 이게 먹히면 IP 기반 판단을 클라이언트가 마음대로 속일 수 있다")
                .isNotEmpty()
                .doesNotContain(FORGED_IP);
    }

    @Test
    @DisplayName("신뢰 프록시가 붙인 X-Forwarded-For 는 remote address 로 반영된다")
    void x_forwarded_for_from_a_trusted_proxy_is_honoured() throws Exception {
        get("X-Forwarded-For", PROXIED_IP);

        assertThat(RemoteAddressCapture.OBSERVED)
                .as("반영되지 않으면 모든 요청이 Caddy 컨테이너 IP 하나로 접혀 설정을 넣은 의미가 없다")
                .contains(PROXIED_IP);
    }

    /**
     * proto 축은 IP 축과 별개 배선이다. {@code RemoteIpValve} 가 {@code X-Forwarded-Proto} 를 읽어
     * {@code setSecure(true)} 를 세워야만 Security 의 {@code HstsHeaderWriter} 가 동작한다 — 그 헤더 이름이나
     * 신뢰 프록시 기본값이 바뀌면 운영에서 HSTS 가 조용히 사라진다. HSTS <b>값</b>은 라이터 축인
     * {@link SecurityHeadersIntegrationTest} 가 이미 못박았으므로 여기서는 배선 신호인 <b>존재</b>만 본다
     * (두 곳에 값을 박으면 의도적 정책 변경이 정보 없이 두 번 빨개진다).
     */
    @Test
    @DisplayName("신뢰 프록시가 붙인 X-Forwarded-Proto 는 secure 판정으로 반영돼 HSTS 를 켠다")
    void x_forwarded_proto_from_a_trusted_proxy_enables_hsts() throws Exception {
        HttpResponse<Void> response = get("X-Forwarded-Proto", "https");

        assertThat(response.headers().firstValue("Strict-Transport-Security"))
                .as("반영되지 않으면 Caddy 뒤 실제 HTTPS 응답에 HSTS 가 빠져 다운그레이드 공격 방어가 사라진다")
                .isPresent();
    }

    /** 인증 여부와 무관하게 valve 는 실행되므로 응답 코드는 보지 않는다 — IP 축은 valve 가 세운 remote address 를, proto 축은 응답 헤더를 본다. */
    private HttpResponse<Void> get(String headerName, String headerValue) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:%d/api/v1/assets".formatted(port)))
                .header(headerName, headerValue)
                .GET()
                .build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request, HttpResponse.BodyHandlers.discarding());
        }
    }

    @TestConfiguration
    static class TestConfig {

        @Bean
        @ServiceConnection
        PostgreSQLContainer postgresContainer() {
            return new PostgreSQLContainer("postgres:16-alpine");
        }

        /**
         * 체인의 <b>맨 끝</b>에 둔다. 여기서 읽은 값이 어떤 방식으로 처리됐든 최종 결과다 — valve(native)는 필터보다
         * 앞이고 ForwardedHeaderFilter(framework)는 필터 체인 앞쪽이라, 앞에 두면 framework 로 바뀌었을 때
         * 필터가 세운 값을 못 보고 테스트가 조용히 통과한다(실측 확인).
         */
        @Bean
        FilterRegistrationBean<Filter> remoteAddressCapture() {
            FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>(new RemoteAddressCapture());
            registration.setOrder(Ordered.LOWEST_PRECEDENCE);
            return registration;
        }
    }

    static class RemoteAddressCapture implements Filter {

        static final List<String> OBSERVED = new CopyOnWriteArrayList<>();

        @Override
        public void doFilter(
                jakarta.servlet.ServletRequest request,
                jakarta.servlet.ServletResponse response,
                jakarta.servlet.FilterChain chain)
                throws IOException, jakarta.servlet.ServletException {
            OBSERVED.add(request.getRemoteAddr());
            chain.doFilter(request, response);
        }
    }
}

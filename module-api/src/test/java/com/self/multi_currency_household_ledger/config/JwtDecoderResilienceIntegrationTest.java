package com.self.multi_currency_household_ledger.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.JWKSetBasedJWKSource;
import com.nimbusds.jose.jwk.source.JWKSetSource;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.RefreshAheadCachingJWKSetSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.self.multi_currency_household_ledger.ledger.controller.CatalogController;
import com.self.multi_currency_household_ledger.ledger.dto.AssetResponse;
import com.self.multi_currency_household_ledger.ledger.service.CatalogService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.mapping.JpaMetamodelMappingContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * JWKS 조회가 <b>느리거나 한 번 실패해도</b> 유효 토큰이 통과하는지 고정한다.
 *
 * <p>운영에서 실제로 난 장애다(2026-08-28 ~ 09-01 실측 6건). 토큰은 멀쩡한데 서버가 Supabase 공개키를 못 가져와
 * 401 을 냈고, 원인은 Supabase 지연이 아니라 <b>클라이언트에 마진이 없다</b>는 것이었다 —
 * {@code NimbusJwtDecoder.withJwkSetUri(...)} 의 기본 경로는 {@code RestTemplateWithNimbusDefaultTimeouts} 가
 * connect·read 를 <b>각 500ms 로 하드코딩</b>하고(프로퍼티로 못 바꾼다), {@code refreshAheadCache(false)} 라
 * 캐시 TTL 5분이 끝나는 순간 <b>요청 스레드가 직접 동기 fetch</b> 하며, {@code retrying} 도 꺼져 있어 1회 실패가
 * 곧바로 401 이 됐다. 운영에서 잰 JWKS 실지연은 중앙값 80ms 인데 콜드 커넥션이 714ms 였다.
 *
 * <p>그래서 로컬 JWKS 서버를 <b>느리게(600ms) + 첫 조회는 503</b> 으로 만든다. 이 한 번의 요청이 두 설정을 동시에
 * 고정한다 — 타임아웃을 500ms 기본값으로 되돌리면 600ms 지연에서 죽고, {@code retrying} 을 빼면 첫 503 에서 죽는다.
 * 둘 다 살아 있어야만 200 이 나온다.
 *
 * <p>여기서 고정하지 <b>않는</b> 것: {@code refreshAheadCache} 와 {@code outageTolerant} 는 캐시 TTL(5분)이
 * 지나야 관찰되는데, 테스트에서 5분을 기다릴 수도 없고 TTL 을 테스트용으로 열면 프로덕션에 쓰이지 않는 설정 표면이
 * 생긴다. 두 옵션은 배선만 하고 검증은 이 테스트의 사정 밖에 둔다.
 *
 * <p><b>JWKS 조회를 유발하는 테스트 메서드를 여기에 더 붙이지 마라.</b> 스텁의 503 과 지연은 클래스당 한 번만
 * 소비되는 자원이고({@code JWKS_HITS} 는 static, 컨텍스트와 {@code SERVER} 도 공유된다) 먼저 실행된 메서드가 그것을
 * 다 써버리면 나머지는 캐시된 키셋으로 통과해 아무것도 고정하지 못한다. 다른 조회 시나리오가 필요하면 클래스를 새로
 * 만든다. 아래 배선 검사처럼 <b>조회를 일으키지 않는</b> 단언은 순서와 무관하므로 함께 두어도 된다.
 *
 * <p>키를 <b>EC P-256/ES256</b> 으로 만드는 이유는 {@link JwtDecoderWiringIntegrationTest} 와 같다 — 운영 Supabase
 * JWKS 에는 ES256 키만 있다.
 */
@WebMvcTest(controllers = CatalogController.class)
@Import(SecurityConfig.class)
@TestPropertySource(
        properties = {
            "woni.security.jwt.audience=authenticated",
            "woni.security.cors.allowed-origins=http://localhost:3000"
        })
class JwtDecoderResilienceIntegrationTest {

    private static final String AUDIENCE = "authenticated";
    private static final String SUBJECT = "00000000-0000-0000-0000-000000000001";
    private static final String KEY_ID = "test-key";

    /** 기본 read timeout(500ms)은 넘기고 프로덕션 설정(3s)에는 드는 값. 첫 조회는 지연 없이 503 이라 슬립은 1회뿐이다. */
    private static final Duration SLOW_JWKS_RESPONSE = Duration.ofMillis(600);

    /** 503 1회 + 느린 200 1회. 이 수가 곧 "실패 경로를 실제로 탔다"는 증거다. */
    private static final int EXPECTED_JWKS_FETCHES = 2;

    private static final AtomicInteger JWKS_HITS = new AtomicInteger();

    private static final KeyPair KEY_PAIR = generateKeyPair();
    private static final HttpServer SERVER = startIssuerServer();

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    @SuppressWarnings("UnusedVariable")
    private JpaMetamodelMappingContext jpaMetamodelMappingContext;

    @MockitoBean
    private CatalogService catalogService;

    @Autowired
    private JWKSource<SecurityContext> jwkSource;

    @DynamicPropertySource
    static void issuerUri(DynamicPropertyRegistry registry) {
        registry.add(
                "spring.security.oauth2.resourceserver.jwt.issuer-uri", JwtDecoderResilienceIntegrationTest::issuer);
    }

    @AfterAll
    static void stopServer() {
        SERVER.stop(0);
    }

    @Test
    @DisplayName("JWKS 가 한 번 실패하고 응답도 600ms 걸리는 상황에서 유효 토큰이 통과한다 — 500ms 기본 타임아웃·재시도 부재 회귀 게이트")
    void valid_token_is_accepted_when_jwks_is_slow_and_fails_once() throws Exception {
        given(catalogService.getAssets()).willReturn(List.of(new AssetResponse(3L, "CASH", "현금", "Cash", 3)));

        mockMvc.perform(get("/api/v1/assets").header("Authorization", "Bearer " + token()))
                .andExpect(status().isOk());

        // 200 만 보면 게이트가 조용히 죽는다 — 이 요청이 JWKS 조회를 두 번(503 → 느린 200) 실제로 태웠어야 한다.
        // 기동 워밍 같은 것이 나중에 붙어 503 을 요청 경로 밖에서 소진하면 여기서 잡힌다.
        assertThat(JWKS_HITS.get()).isEqualTo(EXPECTED_JWKS_FETCHES);
    }

    @Test
    @DisplayName("JWKS 조회 계층에 주기 갱신 타이머가 있다 — refreshAheadCache 를 1-arg 로 되돌리면 실패한다")
    void jwk_source_schedules_refresh_ahead() {
        JWKSetSource<?> source = ((JWKSetBasedJWKSource<?>) jwkSource).getJWKSetSource();

        assertThat(source).isInstanceOf(RefreshAheadCachingJWKSetSource.class);
        assertThat(((RefreshAheadCachingJWKSetSource<?>) source).getScheduledExecutorService())
                .as("1-arg refreshAheadCache(true) 는 refreshAheadScheduled 를 켜지 않아 타이머가 만들어지지 않는다 — "
                        + "그러면 갱신은 '만료 30초 전 창에 요청이 들어왔을 때'만 일어나고 만료 직후 첫 요청은 "
                        + "동기 fetch 를 탄다")
                .isNotNull();
    }

    private static String token() throws JOSEException {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(SUBJECT)
                .issuer(issuer())
                .audience(List.of(AUDIENCE))
                .issueTime(Date.from(Instant.now().minusSeconds(60)))
                .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                .claim("role", "authenticated")
                .build();

        SignedJWT signedJwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(KEY_ID).build(), claims);
        signedJwt.sign(new ECDSASigner((ECPrivateKey) KEY_PAIR.getPrivate()));
        return signedJwt.serialize();
    }

    /** {@link JwtDecoderWiringIntegrationTest#issuer()} 와 같은 이유로 바인딩한 주소를 그대로 쓴다. */
    private static String issuer() {
        InetAddress address = SERVER.getAddress().getAddress();
        String host = address instanceof Inet6Address ? "[" + address.getHostAddress() + "]" : address.getHostAddress();
        return "http://" + host + ":" + SERVER.getAddress().getPort();
    }

    private static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            return generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("EC P-256 is unavailable", e);
        }
    }

    private static HttpServer startIssuerServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/.well-known/jwks.json", JwtDecoderResilienceIntegrationTest::handleJwks);
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 첫 조회는 503 으로 끊고(재시도 게이트), 이후 조회는 느리게 응답한다(타임아웃 게이트). */
    private static void handleJwks(HttpExchange exchange) throws IOException {
        if (JWKS_HITS.incrementAndGet() == 1) {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
            return;
        }
        sleepQuietly(SLOW_JWKS_RESPONSE);
        respond(exchange, jwks());
    }

    private static void sleepQuietly(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String jwks() {
        ECKey key = new ECKey.Builder(Curve.P_256, (ECPublicKey) KEY_PAIR.getPublic())
                .keyID(KEY_ID)
                .algorithm(JWSAlgorithm.ES256)
                .build();
        return new JWKSet(key).toString();
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}

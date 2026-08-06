package com.self.multi_currency_household_ledger.config;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
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
import java.time.Instant;
import java.util.Date;
import java.util.List;
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
 * 프로덕션 {@code jwtDecoder()} 빈을 실제로 생성해 배선까지 검증한다 — 다른 모든 통합 테스트는
 * {@code @MockitoBean JwtDecoder} 로 빈 정의를 치환하므로 프로덕션 팩터리가 한 번도 실행되지 않는다.
 *
 * <p>이 테스트가 없으면 {@code jwtDecoder()} 에서 {@code configure(...)} 래핑을 벗겨내도 전 테스트가 그린이다 —
 * 단위 테스트는 {@code configure()} 를 직접 부르므로 그 경로를 지나지 않기 때문이다. JWKS 는 JDK 내장 HTTP 서버로
 * 로컬에서 제공해 외부 네트워크 의존을 만들지 않는다.
 *
 * <p>로컬 서버는 <b>디스커버리 문서를 제공하지 않는다</b>(JWKS 경로만 연다). 이것이 "기동 시 issuer 디스커버리를
 * 타지 않는다"를 고정하는 장치다 — {@code JwtDecoders.fromIssuerLocation(...)} 으로 되돌리면 빈 생성이 404 로
 * 실패해 이 클래스 전체가 무너진다. 디스커버리 왕복은 운영에서 실제로 timeout 이 나 앱을 못 뜨게 했다(2026-08-06,
 * 배포·리부팅에서 각 1회). 얻는 것이 {@code jwks_uri} 하나뿐이라 그 값을 설정으로 직접 준다.
 *
 * <p>JWKS 를 하필 {@code /.well-known/jwks.json} 에 여는 것은 {@code application.yml} 의 파생식
 * ({@code issuer-uri} + 이 경로)을 테스트가 실제로 태우기 위해서다 — {@code jwk-set-uri} 를
 * {@code @DynamicPropertySource} 로 직접 주면 그 식에 오타가 나도 깨지는 테스트가 하나도 없다.
 *
 * <p>키는 <b>EC P-256/ES256</b> 으로 만든다. 운영 Supabase JWKS 에는 ES256 키만 있으므로, 여기서 RS256 으로
 * 서명하면 {@code withJwkSetUri} 의 기본값(RS256 전용)과 우연히 맞아떨어져 "실토큰 전면 거부"를 이 테스트가
 * 가려버린다.
 */
@WebMvcTest(controllers = CatalogController.class)
@Import(SecurityConfig.class)
@TestPropertySource(
        properties = {
            "woni.security.jwt.audience=authenticated",
            "woni.security.cors.allowed-origins=http://localhost:3000"
        })
class JwtDecoderWiringIntegrationTest {

    private static final String AUDIENCE = "authenticated";
    private static final String SUBJECT = "00000000-0000-0000-0000-000000000001";
    private static final String KEY_ID = "test-key";

    private static final KeyPair KEY_PAIR = generateKeyPair();
    private static final HttpServer SERVER = startIssuerServer();

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    @SuppressWarnings("UnusedVariable")
    private JpaMetamodelMappingContext jpaMetamodelMappingContext;

    @MockitoBean
    private CatalogService catalogService;

    @DynamicPropertySource
    static void issuerUri(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", JwtDecoderWiringIntegrationTest::issuer);
    }

    @AfterAll
    static void stopServer() {
        SERVER.stop(0);
    }

    @Test
    @DisplayName("로컬 JWKS 로 서명한 유효 토큰은 프로덕션 디코더를 통과한다")
    void locally_signed_valid_token_is_accepted() throws Exception {
        given(catalogService.getAssets()).willReturn(List.of(new AssetResponse(3L, "CASH", "현금", "Cash", 3)));

        mockMvc.perform(get("/api/v1/assets").header("Authorization", "Bearer " + token(List.of(AUDIENCE))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].code").value("CASH"));
    }

    @Test
    @DisplayName("서명은 유효하지만 audience 가 다른 토큰은 401 — jwtDecoder() 의 configure(...) 배선이 살아 있어야만 통과한다")
    void valid_signature_with_wrong_audience_is_rejected() throws Exception {
        // 본문은 검사하지 않는다 — 유효하지 않은 토큰은 BearerTokenAuthenticationEntryPoint 가 처리해
        // 본문 없이 WWW-Authenticate 만 내려가고, SecurityConfig 의 ErrorResponse 엔트리포인트를 타지 않는다
        // (토큰이 아예 없을 때만 그쪽으로 간다). 이 불일치는 별도 판단 사항이라 여기서 고정하지 않는다.
        mockMvc.perform(get("/api/v1/assets").header("Authorization", "Bearer " + token(List.of("service_role"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("aud 클레임이 없는 토큰은 401 — 검증기 NPE 로 500 이 나가던 회귀를 HTTP 층에서 고정한다")
    void token_without_audience_is_rejected_with_401() throws Exception {
        mockMvc.perform(get("/api/v1/assets").header("Authorization", "Bearer " + token(List.of())))
                .andExpect(status().isUnauthorized());
    }

    private static String token(List<String> audience) throws JOSEException {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .subject(SUBJECT)
                .issuer(issuer())
                .issueTime(Date.from(Instant.now().minusSeconds(60)))
                .expirationTime(Date.from(Instant.now().plusSeconds(300)));
        if (!audience.isEmpty()) {
            claims.audience(audience); // 빈 리스트를 넣으면 aud 가 빈 배열로 실려 "클레임 없음"이 아니게 된다
        }

        SignedJWT signedJwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(KEY_ID).build(), claims.build());
        signedJwt.sign(new ECDSASigner((ECPrivateKey) KEY_PAIR.getPrivate()));
        return signedJwt.serialize();
    }

    /**
     * 바인딩한 주소를 그대로 쓴다 — {@code localhost} 로 조립하면 이름 해석이 {@code ::1} 을 고르는 환경에서
     * 어긋난다. IPv6 루프백은 URL 에서 대괄호로 감싸야 한다({@code http://[::1]:PORT}).
     */
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
            // 디스커버리 경로는 일부러 열지 않고, JWKS 경로는 application.yml 파생식과 같게 둔다 — 클래스 javadoc 참조.
            server.createContext("/.well-known/jwks.json", exchange -> respond(exchange, jwks()));
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
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

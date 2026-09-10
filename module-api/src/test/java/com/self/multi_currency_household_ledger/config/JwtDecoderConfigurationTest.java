package com.self.multi_currency_household_ledger.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * 프로덕션 디코더 설정({@link SecurityConfig#configure})을 네트워크 없이 그대로 태워, 검증기 5종(만료·issuer·audience·exp
 * 필수·role)이 실제로 실행되는지 확인한다. 기존 통합 테스트는 {@code @MockitoBean JwtDecoder} 로 디코더를 통째로 대체하므로 이 층을 한 번도
 * 지나지 않는다.
 */
class JwtDecoderConfigurationTest {

    private static final String ISSUER = "https://example.supabase.co/auth/v1";
    private static final String AUDIENCE = "authenticated";
    private static final String SUBJECT = "00000000-0000-0000-0000-000000000001";
    private static final String AUTHENTICATED_ROLE = "authenticated";

    private static KeyPair keyPair;
    private static NimbusJwtDecoder decoder;

    @BeforeAll
    static void setUpDecoder() throws NoSuchAlgorithmException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair = generator.generateKeyPair();
        decoder = SecurityConfig.configure(
                NimbusJwtDecoder.withPublicKey((RSAPublicKey) keyPair.getPublic())
                        .build(),
                ISSUER,
                AUDIENCE);
    }

    @Test
    @DisplayName("issuer·audience·만료가 모두 유효한 토큰은 디코딩된다(양성 대조군)")
    void valid_token_is_decoded() throws JOSEException {
        Jwt jwt = decoder.decode(token(ISSUER, List.of(AUDIENCE), Instant.now().plusSeconds(300)));

        assertThat(jwt.getSubject()).isEqualTo(SUBJECT);
        assertThat(jwt.getAudience()).containsExactly(AUDIENCE);
    }

    @Test
    @DisplayName("만료된 토큰은 거부된다 — 60초 클록 스큐를 넘어선 exp")
    void expired_token_is_rejected() throws JOSEException {
        String expired = token(ISSUER, List.of(AUDIENCE), Instant.now().minusSeconds(120));

        assertThatThrownBy(() -> decoder.decode(expired))
                .isInstanceOf(JwtValidationException.class)
                .hasMessageContaining("expired");
    }

    @Test
    @DisplayName("만료 직후여도 60초 클록 스큐 안이면 통과한다 — 스큐 값을 위아래로 고정한다")
    void token_expired_within_clock_skew_is_accepted() throws JOSEException {
        Jwt jwt = decoder.decode(token(ISSUER, List.of(AUDIENCE), Instant.now().minusSeconds(30)));

        assertThat(jwt.getSubject()).isEqualTo(SUBJECT);
    }

    @Test
    @DisplayName("iss 클레임이 다른 토큰은 거부된다 — 서명 키는 같다")
    void token_with_other_issuer_claim_is_rejected() throws JOSEException {
        String otherIssuer = token(
                "https://attacker.supabase.co/auth/v1",
                List.of(AUDIENCE),
                Instant.now().plusSeconds(300));

        assertThatThrownBy(() -> decoder.decode(otherIssuer)).isInstanceOf(JwtValidationException.class);
    }

    @Test
    @DisplayName("다른 audience 토큰은 거부된다 — 같은 Supabase 프로젝트의 타 용도 토큰 재사용 차단")
    void token_with_other_audience_is_rejected() throws JOSEException {
        String otherAudience =
                token(ISSUER, List.of("service_role"), Instant.now().plusSeconds(300));

        assertThatThrownBy(() -> decoder.decode(otherAudience)).isInstanceOf(JwtValidationException.class);
    }

    @Test
    @DisplayName("audience 클레임이 없는 토큰은 거부된다")
    void token_without_audience_is_rejected() throws JOSEException {
        String noAudience = token(ISSUER, List.of(), Instant.now().plusSeconds(300));

        assertThatThrownBy(() -> decoder.decode(noAudience)).isInstanceOf(JwtValidationException.class);
    }

    @Test
    @DisplayName("exp 클레임이 없는 토큰은 거부된다 — JwtTimestampValidator 는 exp 부재를 통과시킨다")
    void token_without_exp_is_rejected() throws JOSEException {
        String noExpiry = token(ISSUER, List.of(AUDIENCE), null, AUTHENTICATED_ROLE);

        assertThatThrownBy(() -> decoder.decode(noExpiry)).isInstanceOf(JwtValidationException.class);
    }

    @Test
    @DisplayName("role 클레임이 없는 토큰은 거부된다 — Supabase 사용자 토큰은 익명 로그인도 role 을 담는다")
    void token_without_role_is_rejected() throws JOSEException {
        String noRole = token(ISSUER, List.of(AUDIENCE), Instant.now().plusSeconds(300), null);

        assertThatThrownBy(() -> decoder.decode(noRole)).isInstanceOf(JwtValidationException.class);
    }

    @Test
    @DisplayName("role=service_role 토큰은 거부된다 — 서버 전용 토큰이 사용자 API 를 타지 못한다")
    void token_with_service_role_is_rejected() throws JOSEException {
        String serviceRole = token(ISSUER, List.of(AUDIENCE), Instant.now().plusSeconds(300), "service_role");

        assertThatThrownBy(() -> decoder.decode(serviceRole)).isInstanceOf(JwtValidationException.class);
    }

    @Test
    @DisplayName("role=anon 토큰은 거부된다 — 로그인 전 anon 키 토큰은 회원 토큰이 아니다")
    void token_with_anon_role_is_rejected() throws JOSEException {
        String anonRole = token(ISSUER, List.of(AUDIENCE), Instant.now().plusSeconds(300), "anon");

        assertThatThrownBy(() -> decoder.decode(anonRole)).isInstanceOf(JwtValidationException.class);
    }

    @Test
    @DisplayName("role 이 배열로 온 토큰도 검증 실패로 거부된다 — 검증기 타입 인자가 String 이면 ClassCastException 이 500 으로 샌다")
    void token_with_array_role_is_rejected_not_thrown() throws JOSEException {
        String arrayRole =
                token(ISSUER, List.of(AUDIENCE), Instant.now().plusSeconds(300), List.of(AUTHENTICATED_ROLE));

        assertThatThrownBy(() -> decoder.decode(arrayRole)).isInstanceOf(JwtValidationException.class);
    }

    private static String token(String issuer, List<String> audience, Instant expiresAt) throws JOSEException {
        return token(issuer, audience, expiresAt, AUTHENTICATED_ROLE);
    }

    /**
     * @param expiresAt {@code null} 이면 exp 클레임을 아예 싣지 않는다 — {@code JwtTimestampValidator} 는 exp 가 없는
     *     토큰을 통과시키므로, 그 구멍을 메우는 검증기는 클레임을 생략할 수 있어야 테스트된다.
     * @param role {@code null} 이면 role 클레임을 싣지 않는다. 타입이 {@code Object} 인 것은 배열처럼 문자열이 아닌 값도
     *     실어 보내 검증기가 {@code ClassCastException} 없이 거부하는지 보기 위해서다.
     */
    private static String token(String issuer, List<String> audience, Instant expiresAt, Object role)
            throws JOSEException {
        // exp 가 iat 보다 앞서면 Nimbus 가 검증기 실행 전에 BadJwtException 으로 잘라낸다.
        // 만료 케이스도 검증기(JwtTimestampValidator)까지 도달해야 하므로 iat 를 exp 기준으로 잡는다.
        Instant issuedAt = (expiresAt != null ? expiresAt : Instant.now()).minusSeconds(300);
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .subject(SUBJECT)
                .issuer(issuer)
                .issueTime(Date.from(issuedAt))
                .claim("role", role); // null 을 주면 Nimbus 가 그 클레임을 담지 않는다
        if (expiresAt != null) {
            claims.expirationTime(Date.from(expiresAt));
        }
        if (!audience.isEmpty()) {
            claims.audience(audience);
        }

        SignedJWT signedJwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims.build());
        signedJwt.sign(new RSASSASigner((RSAPrivateKey) keyPair.getPrivate()));
        return signedJwt.serialize();
    }
}

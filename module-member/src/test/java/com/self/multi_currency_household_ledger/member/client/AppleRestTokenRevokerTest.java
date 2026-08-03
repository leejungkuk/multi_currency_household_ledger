package com.self.multi_currency_household_ledger.member.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

class AppleRestTokenRevokerTest {

    private static final String CLIENT_ID = "test-client-id";
    private static final String TEAM_ID = "test-team-id";
    private static final String KEY_ID = "test-key-id";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(2);

    private WireMockServer wireMock;
    private KeyPair keyPair;
    private String privateKeyBase64;
    private AppleRestTokenRevoker revoker;

    @BeforeEach
    void setUp() throws Exception {
        wireMock = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wireMock.start();

        keyPair = generateKeyPair("secp256r1");
        privateKeyBase64 = encodePrivateKey(keyPair);
        revoker = createRevoker(CLIENT_ID, TEAM_ID, KEY_ID, privateKeyBase64, READ_TIMEOUT, false);
    }

    @AfterEach
    void tearDown() {
        wireMock.stop();
    }

    @Test
    @DisplayName("authorization code 교환과 refresh token revoke가 성공하면 REVOKED를 반환한다")
    void revokes_apple_refresh_token() {
        stubTokenSuccess();
        stubRevokeSuccess();

        assertThat(revoker.revoke("authorization-code")).isEqualTo(RevokeOutcome.REVOKED);
        wireMock.verify(1, postRequestedFor(urlPathEqualTo("/auth/token")));
        wireMock.verify(1, postRequestedFor(urlPathEqualTo("/auth/revoke")));
    }

    @Test
    @DisplayName("client_secret은 Apple 규격 ES256 JWT이며 네이티브 교환 폼에는 redirect_uri가 없다")
    void creates_valid_apple_client_secret() throws Exception {
        stubTokenSuccess();
        stubRevokeSuccess();

        assertThat(revoker.revoke("authorization-code")).isEqualTo(RevokeOutcome.REVOKED);

        Map<String, String> tokenForm = requestForm("/auth/token");
        SignedJWT clientSecret = SignedJWT.parse(tokenForm.get("client_secret"));
        assertThat(clientSecret.verify(new ECDSAVerifier((ECPublicKey) keyPair.getPublic())))
                .isTrue();
        assertThat(clientSecret.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.ES256);
        assertThat(clientSecret.getHeader().getKeyID()).isEqualTo(KEY_ID);
        assertThat(clientSecret.getJWTClaimsSet().getIssuer()).isEqualTo(TEAM_ID);
        assertThat(clientSecret.getJWTClaimsSet().getSubject()).isEqualTo(CLIENT_ID);
        assertThat(clientSecret.getJWTClaimsSet().getAudience()).containsExactly("https://appleid.apple.com");
        assertThat(Duration.between(
                        clientSecret.getJWTClaimsSet().getIssueTime().toInstant(),
                        clientSecret.getJWTClaimsSet().getExpirationTime().toInstant()))
                .isEqualTo(Duration.ofMinutes(5));
        assertThat(tokenForm)
                .containsEntry("client_id", CLIENT_ID)
                .containsEntry("code", "authorization-code")
                .containsEntry("grant_type", "authorization_code")
                .doesNotContainKey("redirect_uri");
        assertThat(request("/auth/token").getHeader("Content-Type")).startsWith("application/x-www-form-urlencoded");
        assertThat(requestForm("/auth/revoke"))
                .containsEntry("client_id", CLIENT_ID)
                .containsEntry("client_secret", tokenForm.get("client_secret"))
                .containsEntry("token", "refresh-token")
                .containsEntry("token_type_hint", "refresh_token");
        assertThat(request("/auth/revoke").getHeader("Content-Type")).startsWith("application/x-www-form-urlencoded");
    }

    @Test
    @DisplayName("token 교환 4xx는 FAILED로 접고 revoke를 호출하지 않는다")
    void returns_failed_when_token_exchange_returns_4xx() {
        wireMock.stubFor(post(urlPathEqualTo("/auth/token"))
                .willReturn(aResponse().withStatus(400).withBody("sensitive-response-body")));
        AtomicReference<RevokeOutcome> outcome = new AtomicReference<>();
        AtomicReference<List<ILoggingEvent>> logs = new AtomicReference<>();

        assertThatCode(() -> logs.set(captureLogs(() -> outcome.set(revoker.revoke("authorization-code")))))
                .doesNotThrowAnyException();

        assertThat(outcome).hasValue(RevokeOutcome.FAILED);
        wireMock.verify(1, postRequestedFor(urlPathEqualTo("/auth/token")));
        wireMock.verify(0, postRequestedFor(urlPathEqualTo("/auth/revoke")));
        assertNoSensitiveLogData(logs.get(), requestForm("/auth/token").get("client_secret"));
    }

    @Test
    @DisplayName("token 교환 5xx는 FAILED로 반환한다")
    void returns_failed_when_token_exchange_returns_5xx() {
        wireMock.stubFor(
                post(urlPathEqualTo("/auth/token")).willReturn(aResponse().withStatus(500)));

        assertThat(revoker.revoke("authorization-code")).isEqualTo(RevokeOutcome.FAILED);
        wireMock.verify(1, postRequestedFor(urlPathEqualTo("/auth/token")));
        wireMock.verify(0, postRequestedFor(urlPathEqualTo("/auth/revoke")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"refresh_token\":\"\"}", "{\"refresh_token\":\"   \"}"})
    @DisplayName("token 교환 응답에 유효한 refresh_token이 없으면 FAILED로 반환하고 revoke를 호출하지 않는다")
    void returns_failed_when_refresh_token_has_no_text(String responseBody) {
        wireMock.stubFor(post(urlPathEqualTo("/auth/token"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody(responseBody)));

        assertThat(revoker.revoke("authorization-code")).isEqualTo(RevokeOutcome.FAILED);
        wireMock.verify(1, postRequestedFor(urlPathEqualTo("/auth/token")));
        wireMock.verify(0, postRequestedFor(urlPathEqualTo("/auth/revoke")));
    }

    @Test
    @DisplayName("revoke 4xx는 예외를 유출하지 않고 FAILED로 반환한다")
    void returns_failed_when_revoke_returns_4xx() {
        stubTokenSuccess();
        wireMock.stubFor(post(urlPathEqualTo("/auth/revoke"))
                .willReturn(aResponse().withStatus(400).withBody("sensitive-response-body")));
        AtomicReference<RevokeOutcome> outcome = new AtomicReference<>();
        AtomicReference<List<ILoggingEvent>> logs = new AtomicReference<>();

        assertThatCode(() -> logs.set(captureLogs(() -> outcome.set(revoker.revoke("authorization-code")))))
                .doesNotThrowAnyException();

        assertThat(outcome).hasValue(RevokeOutcome.FAILED);
        wireMock.verify(1, postRequestedFor(urlPathEqualTo("/auth/token")));
        wireMock.verify(1, postRequestedFor(urlPathEqualTo("/auth/revoke")));
        assertNoSensitiveLogData(logs.get(), requestForm("/auth/token").get("client_secret"));
    }

    @Test
    @DisplayName("revoke 5xx는 FAILED로 반환한다")
    void returns_failed_when_revoke_returns_5xx() {
        stubTokenSuccess();
        wireMock.stubFor(
                post(urlPathEqualTo("/auth/revoke")).willReturn(aResponse().withStatus(500)));

        assertThat(revoker.revoke("authorization-code")).isEqualTo(RevokeOutcome.FAILED);
        wireMock.verify(1, postRequestedFor(urlPathEqualTo("/auth/token")));
        wireMock.verify(1, postRequestedFor(urlPathEqualTo("/auth/revoke")));
    }

    @Test
    @DisplayName("token 교환 타임아웃은 재시도나 예외 유출 없이 FAILED로 반환한다")
    void returns_failed_on_token_timeout() {
        revoker = createRevoker(CLIENT_ID, TEAM_ID, KEY_ID, privateKeyBase64, Duration.ofMillis(100), false);
        wireMock.stubFor(post(urlPathEqualTo("/auth/token"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withFixedDelay(500)
                        .withBody("{\"refresh_token\":\"refresh-token\"}")));
        AtomicReference<RevokeOutcome> outcome = new AtomicReference<>();

        assertThatCode(() -> outcome.set(revoker.revoke("authorization-code"))).doesNotThrowAnyException();

        assertThat(outcome).hasValue(RevokeOutcome.FAILED);
        wireMock.verify(1, postRequestedFor(urlPathEqualTo("/auth/token")));
        wireMock.verify(0, postRequestedFor(urlPathEqualTo("/auth/revoke")));
    }

    @Test
    @DisplayName("revoke 타임아웃은 재시도나 예외 유출 없이 FAILED로 반환한다")
    void returns_failed_on_revoke_timeout() {
        revoker = createRevoker(CLIENT_ID, TEAM_ID, KEY_ID, privateKeyBase64, Duration.ofMillis(100), false);
        stubTokenSuccess();
        wireMock.stubFor(
                post(urlPathEqualTo("/auth/revoke")).willReturn(aResponse().withFixedDelay(500)));
        AtomicReference<RevokeOutcome> outcome = new AtomicReference<>();

        assertThatCode(() -> outcome.set(revoker.revoke("authorization-code"))).doesNotThrowAnyException();

        assertThat(outcome).hasValue(RevokeOutcome.FAILED);
        wireMock.verify(1, postRequestedFor(urlPathEqualTo("/auth/token")));
        wireMock.verify(1, postRequestedFor(urlPathEqualTo("/auth/revoke")));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    @DisplayName("authorization code가 null 또는 공백이면 SKIPPED이며 HTTP 요청을 보내지 않는다")
    void skips_when_authorization_code_has_no_text(String authorizationCode) {
        assertThat(revoker.revoke(authorizationCode)).isEqualTo(RevokeOutcome.SKIPPED);
        assertThat(wireMock.getAllServeEvents()).isEmpty();
    }

    @Test
    @DisplayName("네 설정이 모두 비어 있고 required=false이면 비활성으로 기동한다")
    void starts_disabled_when_configuration_is_empty_and_not_required() {
        AppleRestTokenRevoker disabled = createRevoker("", "", "", "", READ_TIMEOUT, false);

        assertThat(disabled.revoke("authorization-code")).isEqualTo(RevokeOutcome.SKIPPED);
        assertThat(wireMock.getAllServeEvents()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("일부 설정만 있으면 required 값과 무관하게 생성자에서 실패한다")
    void rejects_partial_configuration(boolean required) {
        assertThatThrownBy(() -> createRevoker(CLIENT_ID, "", "", "", READ_TIMEOUT, required))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("1/4")
                .hasMessageNotContaining(CLIENT_ID);
    }

    @Test
    @DisplayName("required=true인데 네 설정이 모두 비어 있으면 생성자에서 실패한다")
    void rejects_empty_configuration_when_required() {
        assertThatThrownBy(() -> createRevoker("", "", "", "", READ_TIMEOUT, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("0/4");
    }

    @Test
    @DisplayName("네 설정이 모두 있고 required=true이면 활성으로 기동해 revoke한다")
    void starts_enabled_when_configuration_is_complete_and_required() {
        AppleRestTokenRevoker requiredRevoker =
                createRevoker(CLIENT_ID, TEAM_ID, KEY_ID, privateKeyBase64, READ_TIMEOUT, true);
        stubTokenSuccess();
        stubRevokeSuccess();

        assertThat(requiredRevoker.revoke("authorization-code")).isEqualTo(RevokeOutcome.REVOKED);
        wireMock.verify(1, postRequestedFor(urlPathEqualTo("/auth/token")));
        wireMock.verify(1, postRequestedFor(urlPathEqualTo("/auth/revoke")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-base64", "bm90LXBlbQ=="})
    @DisplayName("base64 또는 PEM 형식이 잘못된 개인키는 생성자에서 실패한다")
    void rejects_invalid_private_key(String invalidPrivateKey) {
        assertThatThrownBy(() -> createRevoker(CLIENT_ID, TEAM_ID, KEY_ID, invalidPrivateKey, READ_TIMEOUT, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining(invalidPrivateKey);
    }

    @ParameterizedTest
    @ValueSource(strings = {"secp384r1", "secp521r1"})
    @DisplayName("ES256으로 서명할 수 없는 EC 개인키는 생성자에서 거부한다")
    void rejects_ec_private_key_that_does_not_support_es256(String curveName) throws Exception {
        String unsupportedPrivateKey = encodePrivateKey(generateKeyPair(curveName));

        assertThatThrownBy(() -> createRevoker(CLIENT_ID, TEAM_ID, KEY_ID, unsupportedPrivateKey, READ_TIMEOUT, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining(unsupportedPrivateKey);
    }

    private AppleRestTokenRevoker createRevoker(
            String clientId,
            String teamId,
            String keyId,
            String encodedPrivateKey,
            Duration readTimeout,
            boolean required) {
        return new AppleRestTokenRevoker(
                RestClient.builder(),
                clientId,
                teamId,
                keyId,
                encodedPrivateKey,
                wireMock.baseUrl(),
                CONNECT_TIMEOUT,
                readTimeout,
                required);
    }

    private void stubTokenSuccess() {
        wireMock.stubFor(post(urlPathEqualTo("/auth/token"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"refresh_token\":\"refresh-token\"}")));
    }

    private void stubRevokeSuccess() {
        wireMock.stubFor(
                post(urlPathEqualTo("/auth/revoke")).willReturn(aResponse().withStatus(200)));
    }

    private Map<String, String> requestForm(String path) {
        return Arrays.stream(request(path).getBodyAsString().split("&"))
                .map(entry -> entry.split("=", 2))
                .collect(Collectors.toMap(
                        entry -> URLDecoder.decode(entry[0], StandardCharsets.UTF_8),
                        entry -> URLDecoder.decode(entry[1], StandardCharsets.UTF_8)));
    }

    private LoggedRequest request(String path) {
        return wireMock.findAll(postRequestedFor(urlPathEqualTo(path))).getFirst();
    }

    private static List<ILoggingEvent> captureLogs(Runnable action) {
        Logger logger = (Logger) LoggerFactory.getLogger(AppleRestTokenRevoker.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            action.run();
            return List.copyOf(appender.list);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    private static void assertNoSensitiveLogData(List<ILoggingEvent> logs, String clientSecret) {
        assertThat(logs).isNotEmpty().allSatisfy(event -> {
            assertThat(event.getFormattedMessage())
                    .doesNotContain("sensitive-response-body", "authorization-code", "refresh-token", clientSecret);
            assertThat(event.getThrowableProxy()).isNull();
        });
    }

    private static KeyPair generateKeyPair(String curveName) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec(curveName));
        return generator.generateKeyPair();
    }

    private static String encodePrivateKey(KeyPair pair) {
        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8))
                        .encodeToString(pair.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
        return Base64.getEncoder().encodeToString(pem.getBytes(StandardCharsets.UTF_8));
    }
}

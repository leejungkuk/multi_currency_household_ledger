package com.self.multi_currency_household_ledger.member.client;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Slf4j
@Component
public class AppleRestTokenRevoker implements AppleTokenRevoker {

    private static final String APPLE_AUDIENCE = "https://appleid.apple.com";
    private static final Duration CLIENT_SECRET_TTL = Duration.ofMinutes(5);

    private final RestClient restClient;
    private final String clientId;
    private final String teamId;
    private final String keyId;
    private final ECDSASigner signer;
    private final String baseUrl;
    private final boolean enabled;

    public AppleRestTokenRevoker(
            RestClient.Builder restClientBuilder,
            @Value("${apple.siwa.client-id:}") String clientId,
            @Value("${apple.siwa.team-id:}") String teamId,
            @Value("${apple.siwa.key-id:}") String keyId,
            @Value("${apple.siwa.private-key-base64:}") String privateKeyBase64,
            @Value("${apple.siwa.base-url:https://appleid.apple.com}") String baseUrl,
            @Value("${apple.siwa.connect-timeout:2s}") Duration connectTimeout,
            @Value("${apple.siwa.read-timeout:3s}") Duration readTimeout,
            @Value("${apple.siwa.required:false}") boolean required) {
        int configuredCount = countConfigured(clientId, teamId, keyId, privateKeyBase64);
        if (configuredCount > 0 && configuredCount < 4) {
            throw new IllegalStateException(
                    "Apple SIWA configuration is incomplete (configured " + configuredCount + "/4)");
        }
        if (configuredCount == 0 && required) {
            throw new IllegalStateException("Apple SIWA configuration is required (configured 0/4)");
        }

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(readTimeout);
        this.restClient = restClientBuilder.requestFactory(requestFactory).build();
        this.clientId = clientId;
        this.teamId = teamId;
        this.keyId = keyId;
        this.baseUrl = baseUrl;
        this.enabled = configuredCount == 4;
        this.signer = enabled ? createSigner(privateKeyBase64) : null;
    }

    @Override
    public RevokeOutcome revoke(String authorizationCode) {
        if (!enabled || !StringUtils.hasText(authorizationCode)) {
            return RevokeOutcome.SKIPPED;
        }

        String stage = "token";
        try {
            String clientSecret = createClientSecret();
            Map<String, Object> response = exchangeAuthorizationCode(authorizationCode, clientSecret);
            Object rawRefreshToken = response == null ? null : response.get("refresh_token");
            String refreshToken = rawRefreshToken == null ? null : rawRefreshToken.toString();
            if (!StringUtils.hasText(refreshToken)) {
                log.warn("Apple 토큰 revoke에 실패했습니다. stage=token, status=no_refresh_token");
                return RevokeOutcome.FAILED;
            }

            stage = "revoke";
            revokeRefreshToken(refreshToken, clientSecret);
            return RevokeOutcome.REVOKED;
        } catch (RestClientException e) {
            log.warn("Apple 토큰 revoke에 실패했습니다. stage={}, status={}", stage, statusCode(e));
            return RevokeOutcome.FAILED;
        } catch (JOSEException e) {
            log.warn("Apple 토큰 revoke에 실패했습니다. stage={}, status=n/a", stage);
            return RevokeOutcome.FAILED;
        } catch (RuntimeException e) {
            log.warn("Apple 토큰 revoke에 실패했습니다. stage={}, status=n/a", stage);
            return RevokeOutcome.FAILED;
        }
    }

    private String createClientSecret() throws JOSEException {
        Instant issuedAt = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(teamId)
                .subject(clientId)
                .audience(APPLE_AUDIENCE)
                .issueTime(Date.from(issuedAt))
                .expirationTime(Date.from(issuedAt.plus(CLIENT_SECRET_TTL)))
                .build();
        SignedJWT clientSecret = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(keyId).build(), claims);
        clientSecret.sign(signer);
        return clientSecret.serialize();
    }

    private Map<String, Object> exchangeAuthorizationCode(String authorizationCode, String clientSecret) {
        LinkedMultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        form.add("code", authorizationCode);
        form.add("grant_type", "authorization_code");

        return restClient
                .post()
                .uri(baseUrl + "/auth/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
    }

    private void revokeRefreshToken(String refreshToken, String clientSecret) {
        LinkedMultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        form.add("token", refreshToken);
        form.add("token_type_hint", "refresh_token");

        restClient
                .post()
                .uri(baseUrl + "/auth/revoke")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .toBodilessEntity();
    }

    private static int countConfigured(String... values) {
        int count = 0;
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                count++;
            }
        }
        return count;
    }

    private static ECPrivateKey parsePrivateKey(String privateKeyBase64) {
        try {
            String pem = new String(Base64.getDecoder().decode(privateKeyBase64), StandardCharsets.UTF_8);
            if (!pem.contains("-----BEGIN PRIVATE KEY-----") || !pem.contains("-----END PRIVATE KEY-----")) {
                throw new IllegalArgumentException("PEM markers are missing");
            }
            String encodedKey = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replaceAll("\\s", "");
            byte[] keyBytes = Base64.getDecoder().decode(encodedKey);
            PrivateKey parsedKey = KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
            if (!(parsedKey instanceof ECPrivateKey ecPrivateKey)) {
                throw new IllegalArgumentException("Private key is not an EC key");
            }
            return ecPrivateKey;
        } catch (RuntimeException | java.security.GeneralSecurityException e) {
            throw new IllegalStateException("Failed to parse Apple SIWA private key", e);
        }
    }

    private static ECDSASigner createSigner(String privateKeyBase64) {
        try {
            ECDSASigner signer = new ECDSASigner(parsePrivateKey(privateKeyBase64));
            if (!signer.supportedJWSAlgorithms().contains(JWSAlgorithm.ES256)) {
                throw new IllegalArgumentException("Private key does not support ES256");
            }
            return signer;
        } catch (JOSEException | RuntimeException e) {
            throw new IllegalStateException("Failed to initialize Apple SIWA private key", e);
        }
    }

    private static String statusCode(RestClientException exception) {
        if (exception instanceof HttpStatusCodeException statusException) {
            return Integer.toString(statusException.getStatusCode().value());
        }
        return "n/a";
    }
}

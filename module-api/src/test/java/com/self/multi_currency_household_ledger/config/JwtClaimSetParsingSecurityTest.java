package com.self.multi_currency_household_ledger.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jwt.JWTParser;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * CVE-2025-53864 회귀 방지 — nimbus-jose-jwt 가 깊게 중첩된 JSON 클레임셋에 상한을 두지 않던 문제.
 *
 * <p>이 경로는 <b>인증 전</b>이다. {@code NimbusJwtDecoder.decode} 는 서명을 검증하기 <b>전에</b> {@link JWTParser#parse} 로
 * 토큰을 파싱하므로, 유효한 토큰이 없어도 Bearer 헤더 하나로 실행된다.
 *
 * <p>실측(9.37.3 vs 9.37.4): 취약 버전은 50만 단계 중첩까지 <b>거절 없이 중첩 Map 트리를 전부 만들어낸다</b>(스택은 넘지 않는다 —
 * shaded Gson 이 반복 파싱이라 CVE 설명의 무한 재귀는 이 경로에서 재현되지 않았다). 남는 위험은 요청 하나가 본문 길이에 비례하는 힙·CPU 를
 * 무인증으로 소모시키는 것이고, 9.37.4 가 중첩 상한을 넣어 파싱 단계에서 끊는다.
 *
 * <p>중첩 <b>배열</b>은 취약 버전도 이미 거절하므로, 두 버전을 가르는 것은 중첩 <b>객체</b> 뿐이다 — 그래서 이 형태로 고정한다.
 */
class JwtClaimSetParsingSecurityTest {

    /** 9.37.3 이 통과시키고 9.37.4 가 거절하는 지점(실측: 1만 단계면 이미 갈린다). */
    private static final int NESTING_DEPTH = 10_000;

    @Test
    @DisplayName("깊게 중첩된 클레임셋은 Map 으로 펼쳐지기 전에 파싱 단계에서 거절된다")
    void deeply_nested_claim_set_is_rejected_before_being_materialized() {
        String token = tokenWithNestedClaimSet(NESTING_DEPTH);

        assertThatThrownBy(() -> JWTParser.parse(token).getJWTClaimsSet())
                .as("서명 검증 전 경로라 무인증 요청 하나가 중첩 깊이만큼 힙·CPU 를 태울 수 있다")
                .isInstanceOf(ParseException.class);
    }

    /** {"a":{"a":...{}...}} 형태의 중첩 payload 를 담은 JWS 형태(header.payload.signature) 문자열. */
    private static String tokenWithNestedClaimSet(int depth) {
        String claims = new StringBuilder(depth * 6)
                .repeat("{\"a\":", depth)
                .append("{}")
                .repeat("}", depth)
                .toString();
        return base64Url("{\"alg\":\"RS256\"}") + "." + base64Url(claims) + "." + base64Url("signature");
    }

    private static String base64Url(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}

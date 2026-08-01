package com.self.multi_currency_household_ledger.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.apache.catalina.util.ServerInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 보안 패치가 들어간 라인 위에 있는지를 <b>런타임에 실제로 뜨는 버전</b>으로 확인한다.
 *
 * <p>임시 핀이 살아 있는지 보던 테스트였으나, Boot 4 로 올라오며 핀이 전부 사라져 목적을 바꿨다. 이제 지키는 것은
 * "BOM 이 관리하는 버전이 패치 라인 아래로 내려가지 않는다"이다. 버전을 정하는 주체가 BOM 이므로 판정은
 * {@code build.gradle} 문자열이 아니라 <b>실제 로드된 클래스의 jar 매니페스트</b>로 한다 — 그래야 BOM 되돌림,
 * {@code ext['tomcat.version']} 같은 하드 오버라이드, 전이 의존 충돌을 모두 같은 눈으로 잡는다.
 *
 * <p>바닥선은 BOM 이 관리하는 값으로 잡는다. CVE 가 고쳐진 최소 버전으로 잡으면 그보다 높은 BOM 값을 누가
 * 끌어내려도 통과하기 때문이다 — 실제로 Boot 4 전환 직전까지 있던 {@code ext['tomcat.version'] = '10.1.55'} 가
 * 그 형태의 지뢰였다(BOM 프로퍼티 하드 오버라이드라 Tomcat 11 을 10.1.55 로 강제 다운그레이드한다).
 *
 * <p>nimbus 쪽은 {@link JwtClaimSetParsingSecurityTest} 가 거동으로 잡으므로 여기서 중복하지 않는다.
 */
class DependencySecurityFloorTest {

    /**
     * 현재 BOM(Boot 4.1.0)이 관리하는 값. CVE-2026-22732(Critical) 는 7.0.4 에서 고쳐졌지만 바닥선은 그보다 높은
     * BOM 값으로 잡는다 — 7.0.4 로 잡으면 누가 7.0.5 로 하드 오버라이드해도 통과해 다운그레이드를 놓친다.
     * BOM 을 올릴 때 이 값도 같이 올린다.
     */
    private static final String SPRING_SECURITY_FLOOR = "7.1.0";

    /** 현재 BOM(Boot 4.1.0)이 관리하는 값. Servlet 6.1(Tomcat 11) 라인이라 10.1.x 대의 패치는 모두 포함한다. */
    private static final String TOMCAT_FLOOR = "11.0.22";

    @Test
    @DisplayName("spring-security 는 BOM 이 관리하는 라인 아래로 내려가지 않는다")
    void spring_security_meets_security_floor() {
        String actual = SecurityFilterChain.class.getPackage().getImplementationVersion();

        assertThat(actual).as("spring-security-web jar 매니페스트에서 버전을 읽지 못했다").isNotNull();
        assertThat(compare(actual, SPRING_SECURITY_FLOOR))
                .as(
                        "spring-security-web %s < %s — 다운그레이드다. 7.0.4 미만이면 CVE-2026-22732 재노출",
                        actual, SPRING_SECURITY_FLOOR)
                .isNotNegative();
    }

    @Test
    @DisplayName("embedded tomcat 은 BOM 이 관리하는 라인 아래로 내려가지 않는다")
    void tomcat_meets_security_floor() {
        String actual = ServerInfo.getServerNumber();

        assertThat(compare(actual, TOMCAT_FLOOR))
                .as("tomcat %s < %s — BOM 값이 하드 오버라이드로 끌어내려졌다", actual, TOMCAT_FLOOR)
                .isNotNegative();
    }

    /** "10.1.55.0" 처럼 자릿수가 다를 수 있으므로 숫자 단위로 비교한다. */
    private static int compare(String left, String right) {
        int[] l = parse(left);
        int[] r = parse(right);
        for (int i = 0; i < Math.max(l.length, r.length); i++) {
            int diff = Integer.compare(i < l.length ? l[i] : 0, i < r.length ? r[i] : 0);
            if (diff != 0) {
                return diff;
            }
        }
        return 0;
    }

    private static int[] parse(String version) {
        return Arrays.stream(version.split("\\."))
                .takeWhile(part -> part.matches("\\d+"))
                .mapToInt(Integer::parseInt)
                .toArray();
    }
}

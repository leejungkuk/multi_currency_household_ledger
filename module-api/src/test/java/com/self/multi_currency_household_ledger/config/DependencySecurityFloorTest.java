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
 * <p><b>단, postgresql · jackson 2.x · jackson 3.x 3종은 그 규칙의 예외로 override 값을 하한으로 쓴다.</b>
 * 위 규칙은 override 가 버전을 <b>내리는</b> 방향일 때의 것이고, 이 3종은 루트 {@code build.gradle} 의
 * {@code ext} 가 BOM 값(42.7.11 / 2.21.4 / 3.1.4)을 CVE 패치 라인으로 <b>올린</b> 것이라 BOM 값을 하한으로
 * 잡으면 override 를 지워도 통과한다 — 지키려는 대상이 하한을 통과해버리면 가드가 아니다.
 *
 * <p><b>이 3종의 가드가 잡는 것과 못 잡는 것.</b> 잡는 것은 <b>현재 패치 하한의 회귀</b>다 — 누가 {@code ext}
 * 를 지우거나 BOM 이 되돌아가면 빨개진다. 못 잡는 것은 <b>stale pin</b> 이다 — 새 CVE 가 나와 42.7.13 이
 * 필요해져도 이 테스트는 42.7.12 에서 계속 그린이고, BOM 이 우리 pin 보다 높은 버전을 담게 돼도 pin 이 이긴
 * 상태를 알려주지 않는다. 그쪽 안전망은 Dependabot 이 pin 된 버전에 다시 alert 을 띄우는 것이다. 이 테스트를
 * "CVE 가드"로 읽으면 거짓 안전감이 된다.
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

    /** {@code ext['postgresql.version']} 이 BOM 값 42.7.11 을 끌어올린 값. CVE-2026-54291(HIGH, 채널 바인딩 조용한 다운그레이드). */
    private static final String POSTGRESQL_FLOOR = "42.7.12";

    /** {@code ext['jackson-2-bom.version']} 이 BOM 값 2.21.4 를 끌어올린 값. CVE-2026-59889 · CVE-2026-54515 · GHSA-mhm7-754m-9p8w. */
    private static final String JACKSON2_FLOOR = "2.21.4";

    /** {@code ext['jackson-bom.version']} 이 BOM 값 3.1.4 를 끌어올린 값. CVE-2026-59889. */
    private static final String JACKSON3_FLOOR = "3.1.5";

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

    @Test
    @DisplayName("postgresql 드라이버는 override 로 끌어올린 패치 하한 아래로 내려가지 않는다")
    void postgresql_meets_security_floor() throws ClassNotFoundException {
        // runtimeOnly 라 testCompileClasspath 에 없다 — 타입으로 참조하면 컴파일이 깨진다.
        String actual = Class.forName("org.postgresql.Driver").getPackage().getImplementationVersion();

        assertThat(actual).as("postgresql jar 매니페스트에서 버전을 읽지 못했다").isNotNull();
        assertThat(compare(actual, POSTGRESQL_FLOOR))
                .as("postgresql %s < %s — ext override 가 사라졌다. CVE-2026-54291 재노출", actual, POSTGRESQL_FLOOR)
                .isNotNegative();
    }

    @Test
    @DisplayName("jackson 2.x 는 override 로 끌어올린 패치 하한 아래로 내려가지 않는다")
    void jackson2_meets_security_floor() {
        String actual =
                com.fasterxml.jackson.databind.ObjectMapper.class.getPackage().getImplementationVersion();

        assertThat(actual).as("jackson-databind 2.x jar 매니페스트에서 버전을 읽지 못했다").isNotNull();
        assertThat(compare(actual, JACKSON2_FLOOR))
                .as("jackson-databind %s < %s — ext override 가 사라졌다", actual, JACKSON2_FLOOR)
                .isNotNegative();
    }

    @Test
    @DisplayName("jackson 3.x 는 override 로 끌어올린 패치 하한 아래로 내려가지 않는다")
    void jackson3_meets_security_floor() {
        // Jackson 3 는 tools.jackson 네임스페이스라 2.x 와 클래스 이름이 겹친다 — 양쪽 다 정규명으로 쓴다.
        String actual = tools.jackson.databind.ObjectMapper.class.getPackage().getImplementationVersion();

        assertThat(actual).as("jackson-databind 3.x jar 매니페스트에서 버전을 읽지 못했다").isNotNull();
        assertThat(compare(actual, JACKSON3_FLOOR))
                .as("jackson-databind %s < %s — ext override 가 사라졌다", actual, JACKSON3_FLOOR)
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

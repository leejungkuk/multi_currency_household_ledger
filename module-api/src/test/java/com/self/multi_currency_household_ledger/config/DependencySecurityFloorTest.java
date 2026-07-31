package com.self.multi_currency_household_ledger.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.apache.catalina.util.ServerInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * build.gradle 의 보안 버전 핀이 살아 있는지 <b>런타임에 실제로 뜨는 버전</b>으로 확인한다.
 *
 * <p>핀은 Spring Boot 3.4.x 의 OSS 지원 종료(2025-11-21)를 메우는 임시 조치라, 지우거나 BOM 을 올릴 때 조용히 되돌아가기 쉽다.
 * tomcat 은 취약점이 리버스 프록시와의 해석 차이(요청 스머글링)라 앱 테스트로 재현할 수 없으므로 버전으로 고정한다.
 * nimbus 쪽 핀은 {@link JwtClaimSetParsingSecurityTest} 가 거동으로 잡으므로 여기서 중복하지 않는다.
 */
class DependencySecurityFloorTest {

    /** CVE-2026-24880(요청 스머글링)·CVE-2026-41284·CVE-2025-55752 가 함께 해소되는 지점. */
    private static final String TOMCAT_FLOOR = "10.1.55";

    @Test
    @DisplayName("embedded tomcat 은 요청 스머글링이 수정된 버전 이상이다")
    void tomcat_meets_security_floor() {
        String actual = ServerInfo.getServerNumber();

        assertThat(compare(actual, TOMCAT_FLOOR))
                .as("tomcat %s < %s — Caddy 뒤에 있어 요청 스머글링 전제조건을 충족한다", actual, TOMCAT_FLOOR)
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

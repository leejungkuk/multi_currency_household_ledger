package com.self.multi_currency_household_ledger.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * SecurityConfig 가 /actuator/prometheus 를 permitAll 로 여는 근거는 "actuator 가 공개 API 포트에 없다" 는 전제 하나뿐이다.
 * management.server.port 설정이 사라지면 스크랩 본문(JVM 상태·DB 풀 수치·엔드포인트별 URI)이 8080 에서 무인증으로 노출된다. 통합
 * 테스트는 포트 충돌을 피하려 이 값을 랜덤으로 덮어쓰기 때문에 전제 자체를 검증하지 못하므로, 설정 파일을 직접 읽어 분리가 살아 있는지 고정한다.
 */
class ActuatorPortSeparationTest {

    @Test
    @DisplayName("application.yml 은 actuator 를 애플리케이션 포트에서 분리한다")
    void management_port_is_separated_from_application_port() throws IOException {
        assertThat(property("application.yml", "management.server.port"))
                .as("management.server.port 가 없으면 actuator 가 공개 API 포트로 돌아와 prometheus 가 무인증 노출된다")
                .isNotNull();
    }

    /**
     * 포트 분리만으로는 호스트에서 jar 를 직접 돌리는 경우(IDE 실행)를 못 막는다. 그때 management 포트가 전 인터페이스에 붙으면 같은 LAN 의 아무
     * 기기나 /actuator/prometheus 를 무인증으로 읽는다(실측 확인). 그래서 기본값은 루프백이고, 전 인터페이스 바인딩은 9091 을 호스트로 매핑하지
     * 않는 컨테이너 배포(prod)만 명시적으로 선택한다 — 프로파일을 빼먹으면 안전한 쪽으로 떨어진다.
     */
    @Test
    @DisplayName("management 바인딩 기본값은 루프백이고, 전 인터페이스는 prod 프로파일만 선택한다")
    void management_binds_to_loopback_by_default_and_all_interfaces_only_in_prod() throws IOException {
        assertThat(property("application.yml", "management.server.address"))
                .as("기본값이 전 인터페이스면 IDE 실행만으로 LAN 에 actuator 가 열린다")
                .asString()
                .contains("127.0.0.1");

        assertThat(property("application-prod.yml", "management.server.address"))
                .as("prod 는 컨테이너 안에서 prometheus 가 붙어야 하므로 전 인터페이스 바인딩이 필요하다(9091 은 호스트로 매핑하지 않는다)")
                .asString()
                .contains("0.0.0.0");
    }

    private static Object property(String resource, String key) throws IOException {
        List<PropertySource<?>> sources =
                new YamlPropertySourceLoader().load(resource, new ClassPathResource(resource));
        return sources.getFirst().getProperty(key);
    }
}

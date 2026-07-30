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
        List<PropertySource<?>> sources =
                new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"));

        assertThat(sources.getFirst().getProperty("management.server.port"))
                .as("management.server.port 가 없으면 actuator 가 공개 API 포트로 돌아와 prometheus 가 무인증 노출된다")
                .isNotNull();
    }
}

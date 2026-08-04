package com.self.multi_currency_household_ledger.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * CORS 가드의 회귀 테스트. CORS 와일드카드 금지는 CLAUDE.md 가 CRITICAL 하드룰로 못 박은 항목인데 회귀 감지가 없었다 —
 * fail-fast 3줄을 지워도 전 테스트가 그린이었다.
 *
 * <p>local 전용 빈의 프로파일 가드는 {@code ArchitectureTest} 가 클래스 열거가 아닌 스캔으로 강제한다.
 */
class SecurityGuardsTest {

    private final SecurityConfig securityConfig = new SecurityConfig();

    @Test
    @DisplayName("CORS 허용 origin 에 와일드카드가 있으면 기동에 실패한다")
    void wildcard_origin_fails_fast() {
        assertThatThrownBy(() -> securityConfig.corsConfigurationSource(List.of("*")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> securityConfig.corsConfigurationSource(List.of("https://*.woni.app")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() ->
                        securityConfig.corsConfigurationSource(List.of("https://woni.app", "https://*.example.com")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("CORS 허용 origin 이 비어 있으면 기동에 실패한다 — 공백만 있는 값도 비어 있는 것으로 본다")
    void empty_origins_fail_fast() {
        assertThatThrownBy(() -> securityConfig.corsConfigurationSource(List.of()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> securityConfig.corsConfigurationSource(List.of("  ", "")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("명시 origin 만 있으면 통과하고 앞뒤 공백은 제거되며 자격증명 허용은 켜지지 않는다")
    void explicit_origins_are_accepted_and_trimmed() {
        UrlBasedCorsConfigurationSource source = (UrlBasedCorsConfigurationSource)
                securityConfig.corsConfigurationSource(List.of(" https://woni.app ", "http://localhost:3000"));

        CorsConfiguration configuration = source.getCorsConfigurations().get("/**");
        assertThat(configuration).isNotNull();
        assertThat(configuration.getAllowedOrigins()).containsExactly("https://woni.app", "http://localhost:3000");
        // stateless Bearer JWT 라 자격증명 동반 cross-origin 요청이 필요 없다. 미설정(null)이 기본이라
        // false 단정은 쓸 수 없고, 막아야 하는 회귀는 true 로 되돌아가는 것 하나다.
        assertThat(configuration.getAllowCredentials()).isNotEqualTo(Boolean.TRUE);
    }
}

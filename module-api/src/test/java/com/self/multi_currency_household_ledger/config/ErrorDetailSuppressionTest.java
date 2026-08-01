package com.self.multi_currency_household_ledger.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.web.ErrorProperties;
import org.springframework.boot.autoconfigure.web.WebProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * prod 는 예외 상세를 응답에 싣지 않는다. 예외 흐름은 GlobalExceptionHandler 가 ErrorResponse 로 통일하지만 화이트라벨 경로로 새는
 * 몫이 남고, 그쪽으로 스택트레이스·예외 메시지가 나가면 내부 구조가 노출된다.
 *
 * <p>이 설정의 실패 모드는 <b>완전히 조용하다</b>. 프레임워크가 키 이름을 옮기면 옛 이름은 기동 실패도 경고도 없이 무시되고, 기본값이 마침
 * 같으면 응답도 안 바뀐다 — 못박아 둔 방어만 사라진다. 실제로 Boot 4.0 이 {@code server.error.*} 를 {@code spring.web.error.*}
 * 로 옮겼을 때 이 상태로 방치됐다.
 *
 * <p>그래서 두 축으로 본다. ① 우리가 쓰는 키 경로가 <b>지금 Boot 버전에서 실제로 바인딩되는지</b> ② prod 가 그 경로로 값을 못박고 있는지.
 * ① 없이 ② 만 보면 키가 죽어도 통과하고, ② 없이 ① 만 보면 설정이 빠져도 통과한다.
 */
class ErrorDetailSuppressionTest {

    private static final String STACKTRACE_KEY = "spring.web.error.include-stacktrace";
    private static final String MESSAGE_KEY = "spring.web.error.include-message";

    /**
     * 기본값이 이미 NEVER 라 prod 값을 그대로 바인딩하면 키가 죽어 있어도 NEVER 가 나온다. 기본값이 아닌 ALWAYS 로 넣어야 경로가 살아
     * 있는지 판정된다.
     */
    @Test
    @DisplayName("prod 가 쓰는 키 경로가 현재 Boot 버전에서 실제로 바인딩된다")
    void configured_key_path_still_binds() {
        ErrorProperties bound = bind(Map.of(STACKTRACE_KEY, "always", MESSAGE_KEY, "always"));

        assertThat(bound.getIncludeStacktrace())
                .as("%s 가 더 이상 바인딩되지 않는다 — 프레임워크가 키를 옮겼다", STACKTRACE_KEY)
                .isEqualTo(ErrorProperties.IncludeAttribute.ALWAYS);
        assertThat(bound.getIncludeMessage())
                .as("%s 가 더 이상 바인딩되지 않는다 — 프레임워크가 키를 옮겼다", MESSAGE_KEY)
                .isEqualTo(ErrorProperties.IncludeAttribute.ALWAYS);
    }

    @Test
    @DisplayName("prod 는 스택트레이스와 예외 메시지를 응답에 싣지 않는다")
    void prod_suppresses_error_detail() throws IOException {
        assertThat(property(STACKTRACE_KEY)).as("prod 응답에 스택트레이스가 실린다").isEqualTo("never");
        assertThat(property(MESSAGE_KEY)).as("prod 응답에 예외 메시지가 실린다").isEqualTo("never");
    }

    private static ErrorProperties bind(Map<String, Object> properties) {
        return new Binder(new MapConfigurationPropertySource(properties))
                .bind("spring.web", WebProperties.class)
                .orElseGet(WebProperties::new)
                .getError();
    }

    private static Object property(String key) throws IOException {
        String resource = "application-prod.yml";
        List<PropertySource<?>> sources =
                new YamlPropertySourceLoader().load(resource, new ClassPathResource(resource));
        return sources.getFirst().getProperty(key);
    }
}

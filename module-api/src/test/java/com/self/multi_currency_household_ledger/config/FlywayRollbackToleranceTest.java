package com.self.multi_currency_household_ledger.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.flyway.autoconfigure.FlywayProperties;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.io.ClassPathResource;

/**
 * CD 의 자동 롤백은 Flyway 가 <b>미래 마이그레이션을 무시하는 것</b> 하나에 걸려 있다.
 *
 * <p>배포가 실패해 구 이미지로 되돌릴 때 DB 스키마는 이미 앞서 있다 — 마이그레이션이 먼저 적용된 뒤에 앱이 죽기 때문이다. 그래서 구 이미지의
 * Flyway 는 "적용됐는데 로컬 classpath 에는 없는 버전"을 만나고, {@code *:future} 가 그것을 무시해 주기 때문에 기동한다. 이 패턴을
 * 빼면 그 자리에서 {@code FlywayValidateException: Detected applied migration not resolved locally} 로 <b>기동이
 * 실패한다</b>(실측 확인). 롤백이 필요한 순간은 이미 장애 중인 순간이라, 그때 드러나면 늦다.
 *
 * <p>그런데 이 값은 {@code application.yml} 에 <b>적혀 있지 않다</b> — Boot 기본값에만 기대고 있다. 누가 Flyway 설정을 손보며
 * 무심코 좁혀도(또는 Boot 가 기본값을 바꿔도) 빌드·기동·테스트가 전부 그린이고, 사고가 나야 드러난다. 그 침묵을 깨는 것이 이 테스트의 전부다.
 *
 * <p>판정은 <b>배포가 실제로 읽는 yml 을 바인딩한 유효값</b>으로 한다. 기본값 상수를 그대로 읽으면 yml 오버라이드를 놓치고, yml 만 읽으면
 * (지금처럼) 키가 없을 때 아무것도 판정하지 못한다. DB 는 필요 없다 — 여기서 보는 것은 Flyway 의 동작이 아니라 설정값이다.
 *
 * <p>그래서 두 축으로 본다. ① 우리가 읽는 키 경로가 <b>지금 Boot 버전에서 실제로 바인딩되는지</b>({@link
 * #configured_key_path_still_binds()}) ② 배포 설정의 유효값이 {@code *:future} 를 담고 있는지({@link
 * #tolerates_future_migrations()}). ① 이 없으면 기본값이 마침 우리가 원하는 값이라, Boot 가 prefix 나 키 이름을 옮겨 바인딩이 통째로
 * 죽어도 ② 는 영원히 그린이다 — 실제로 {@code server.error.*} → {@code spring.web.error.*} 이관 때 겪은 실패 모드다.
 */
class FlywayRollbackToleranceTest {

    /** 배포가 읽는 설정 파일을 우선순위 순으로 나열한다(앞이 이긴다). prod 는 Dockerfile 이 기본으로 넣는 프로파일이다. */
    private static final List<String> DEPLOYED_CONFIG = List.of("application-prod.yml", "application.yml");

    /** 구 이미지가 "로컬에 없는, 적용된 마이그레이션"을 만나도 죽지 않게 하는 패턴. Boot 기본값이라 yml 에는 나타나지 않는다. */
    private static final String FUTURE_TOLERANCE = "*:future";

    private static final String PATTERNS_KEY = "spring.flyway.ignore-migration-patterns";

    /**
     * 기본값이 이미 {@code *:future} 라 배포 설정을 그대로 바인딩하면 키가 죽어 있어도 {@code *:future} 가 나온다. 기본값이 아닌 마커를
     * 넣어야 경로가 살아 있는지 판정된다.
     */
    @Test
    @DisplayName("우리가 읽는 키 경로가 현재 Boot 버전에서 실제로 바인딩된다")
    void configured_key_path_still_binds() {
        String marker = "*:marker";

        List<String> bound = new Binder(new MapConfigurationPropertySource(Map.of(PATTERNS_KEY, marker)))
                .bind("spring.flyway", FlywayProperties.class)
                .orElseGet(FlywayProperties::new)
                .getIgnoreMigrationPatterns();

        assertThat(bound)
                .as("%s 가 더 이상 바인딩되지 않는다 — 프레임워크가 키를 옮겼다. 다른 테스트는 Boot 기본값을 읽고 있을 뿐이니 값이 아니라 경로부터 고쳐라.", PATTERNS_KEY)
                .contains(marker);
    }

    @Test
    @DisplayName("Flyway 는 로컬에 없는 미래 마이그레이션을 무시한다 — CD 자동 롤백의 전제")
    void tolerates_future_migrations() throws IOException {
        List<String> effective = effectiveFlywayProperties().getIgnoreMigrationPatterns();

        assertThat(effective)
                .as(
                        "spring.flyway.ignore-migration-patterns = %s 에 %s 가 없다. 이러면 스키마가 앞선 DB 에 붙는 구 이미지가"
                                + " FlywayValidateException(Detected applied migration not resolved locally) 으로 기동에 실패한다"
                                + " — CD 자동 롤백 경로가 통째로 죽는다. 좁히려면 롤백 전략부터 다시 정하라.",
                        effective, FUTURE_TOLERANCE)
                .contains(FUTURE_TOLERANCE);
    }

    /** yml 이 침묵하는 키에 Boot 기본값이 채워지는 실제 바인딩을 그대로 재현한다. */
    private static FlywayProperties effectiveFlywayProperties() throws IOException {
        MutablePropertySources sources = new MutablePropertySources();
        for (String file : DEPLOYED_CONFIG) {
            new YamlPropertySourceLoader()
                    .load(file, new ClassPathResource(file))
                    .forEach(sources::addLast);
        }
        return new Binder(ConfigurationPropertySources.from(sources))
                .bind("spring.flyway", FlywayProperties.class)
                .orElseGet(FlywayProperties::new);
    }
}

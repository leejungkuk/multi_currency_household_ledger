package com.self.multi_currency_household_ledger.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.self.multi_currency_household_ledger.ledger.controller.CatalogController;
import com.self.multi_currency_household_ledger.ledger.service.CatalogService;
import jakarta.servlet.Filter;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.mapping.JpaMetamodelMappingContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.web.util.matcher.RequestMatcherEntry;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 메인 체인의 인가 규칙 <b>집합</b>을 순서까지 고정한다.
 *
 * <p>기존 테스트는 "이 경로가 공개인가"를 경로별로 확인할 뿐이라, permitAll 에 경로를 <b>추가</b>해도 아무것도 실패하지
 * 않았다.
 *
 * <p>이 스냅샷만으로는 컨트롤러에 GET 을 추가하는 경로를 잡을 수 없다는 점에 주의한다 — 규칙 목록이 그대로이기
 * 때문이다. 그 벡터는 {@link SecurityConfig} 가 와일드카드 대신 경로를 열거해서 닫는다(신규 GET 은 기본 401).
 * 둘은 짝이다: 열거가 "공개하려면 명시하라"를 강제하고, 이 스냅샷이 "명시가 늘면 실패한다"를 강제한다.
 *
 * <p>이 스냅샷이 깨지면 규칙을 의도적으로 바꾼 것인지 확인하고 기대값을 갱신한다 — 특히 {@code permitAll} 항목이
 * 늘었다면 그것이 공개돼도 되는 경로인지 판단해야 한다.
 */
@WebMvcTest(controllers = CatalogController.class)
@Import(SecurityConfig.class)
@TestPropertySource(
        properties = {
            "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://example.supabase.co/auth/v1",
            "woni.security.jwt.audience=authenticated",
            "woni.security.cors.allowed-origins=http://localhost:3000"
        })
class PermitAllSnapshotTest {

    private static final List<String> EXPECTED_RULES = List.of(
            "GET /actuator/health -> permitAll",
            "GET /actuator/prometheus -> permitAll",
            "GET /api/v1/exchange-rates -> permitAll",
            "GET /api/v1/exchange-rates/range -> permitAll",
            "GET /api/v1/exchange-rates/snapshot -> permitAll",
            "GET /api/v1/exchange-rates/status -> permitAll",
            "GET /api/v1/exchange-rates/{currencyCode:[A-Z]{3}} -> permitAll",
            "GET /api/v1/categories -> permitAll",
            "GET /api/v1/assets -> permitAll",
            "any request -> AuthenticatedAuthorizationManager");

    /** 매처의 {@code toString()} 은 Spring Security 버전에 따라 감싸는 형태가 달라진다(예: {@code Deferred [Mvc [...], Ant [...]]}). 스냅샷이 프레임워크 내부 표현이 아니라 규칙 자체를 고정하도록 메서드·패턴만 뽑아 쓴다. */
    private static final Pattern MATCHER_PATTERN = Pattern.compile("pattern='([^']+)'(?:, ([A-Z]+))?");

    @Autowired
    private Filter springSecurityFilterChain;

    @MockitoBean
    @SuppressWarnings("UnusedVariable")
    private JwtDecoder jwtDecoder;

    @MockitoBean
    @SuppressWarnings("UnusedVariable")
    private JpaMetamodelMappingContext jpaMetamodelMappingContext;

    @MockitoBean
    @SuppressWarnings("UnusedVariable")
    private CatalogService catalogService;

    @Test
    @DisplayName("메인 체인의 인가 규칙 집합이 스냅샷과 정확히 일치한다")
    void authorization_rules_match_snapshot() {
        assertThat(authorizationRules()).containsExactlyElementsOf(EXPECTED_RULES);
    }

    private List<String> authorizationRules() {
        AuthorizationFilter authorizationFilter = authorizationFilter();
        Object manager = authorizationFilter.getAuthorizationManager();
        List<?> mappings = (List<?>) ReflectionTestUtils.getField(manager, "mappings");
        assertThat(mappings).isNotNull();

        return mappings.stream()
                .map(RequestMatcherEntry.class::cast)
                .map(entry -> describe(entry.getRequestMatcher()) + " -> " + decision(entry.getEntry()))
                .toList();
    }

    private static String describe(Object requestMatcher) {
        String raw = String.valueOf(requestMatcher);
        Matcher matcher = MATCHER_PATTERN.matcher(raw);
        if (!matcher.find()) {
            // 미인식 매처를 "any request" 로 접으면 프레임워크가 toString 포맷을 바꿨을 때
            // 모든 규칙이 한 줄로 뭉개진 채 스냅샷만 갱신되고 넘어갈 수 있다.
            if (raw.contains("any request")) {
                return "any request";
            }
            throw new AssertionError("매처 표현을 해석하지 못했다 — 정규식을 갱신해야 한다: " + raw);
        }
        return matcher.group(2) == null ? matcher.group(1) : matcher.group(2) + " " + matcher.group(1);
    }

    /**
     * 인증 없는 요청에 대한 판정. 거부되는 규칙은 매니저 구현 클래스명으로 적는다 — "denied" 로 뭉뚱그리면
     * {@code authenticated()} 가 {@code denyAll()} 이나 권한 기반 규칙으로 바뀌어도 스냅샷이 그대로다.
     */
    @SuppressWarnings("unchecked")
    private static String decision(Object entry) {
        AuthorizationManager<RequestAuthorizationContext> manager =
                (AuthorizationManager<RequestAuthorizationContext>) entry;
        AuthorizationResult result =
                manager.authorize(() -> null, new RequestAuthorizationContext(new MockHttpServletRequest()));
        return result != null && result.isGranted()
                ? "permitAll"
                : manager.getClass().getSimpleName();
    }

    private AuthorizationFilter authorizationFilter() {
        List<?> chains = (List<?>) ReflectionTestUtils.invokeGetterMethod(springSecurityFilterChain, "getFilterChains");

        return chains.stream()
                .map(chain -> (List<?>) ReflectionTestUtils.invokeGetterMethod(chain, "getFilters"))
                .flatMap(List::stream)
                .filter(AuthorizationFilter.class::isInstance)
                .map(AuthorizationFilter.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("AuthorizationFilter 를 찾지 못했다"));
    }
}

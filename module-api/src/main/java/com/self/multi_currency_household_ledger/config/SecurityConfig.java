package com.self.multi_currency_household_ledger.config;

import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.DefaultResourceRetriever;
import com.self.multi_currency_household_ledger.common.dto.ErrorResponse;
import com.self.multi_currency_household_ledger.common.exception.ErrorCode;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import tools.jackson.databind.ObjectMapper;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * JWKS 조회의 connect·read 타임아웃. Nimbus 기본값 <b>500ms</b> 는 운영 실측에 마진이 없다 — A1 컨테이너에서 잰
     * Supabase JWKS 응답은 20/20 성공에 중앙값 80ms 지만 <b>콜드 커넥션이 714ms</b> 였다. 서버가 느린 게 아니라
     * 예산이 좁아서 나던 실패다.
     */
    private static final Duration JWKS_HTTP_TIMEOUT = Duration.ofSeconds(3);

    /**
     * 캐시가 만료된 뒤 <b>갱신 조회마저 실패할 때만</b> 마지막으로 받은 키로 검증을 이어가는 한도. Supabase JWKS 가
     * 잠깐 흔들려도 그것이 곧바로 전 사용자 인증 실패가 되지 않게 한다. 폐기된 키를 이 시간 내내 신뢰한다는 뜻이
     * <b>아니다</b> — {@code OutageTolerantJWKSetSource} 는 {@code JWKSetUnavailableException} 을 잡았을 때만 옛
     * 키셋으로 폴백하고, 조회가 한 번이라도 성공하면 즉시 최신 키셋으로 갈아탄다.
     */
    private static final Duration JWKS_OUTAGE_TOLERANCE = Duration.ofHours(1);

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            CorsConfigurationSource corsConfigurationSource,
            ObjectMapper objectMapper,
            ObjectProvider<RateLimitFilter> rateLimitFilterProvider)
            throws Exception {
        http.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Stateless bearer-token REST API라 브라우저 세션 쿠키 기반 CSRF 토큰이 필요하지 않다.
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .authorizeHttpRequests(authorize -> authorize
                        // security-ack: 배포 플랫폼·컨테이너 재시작 판정과 외부 모니터링이 토큰 없이 읽어야 하는
                        // liveness 신호. show-details=never 라 응답은 {"status":"UP"} 뿐이고 GET 만 연다.
                        .requestMatchers(HttpMethod.GET, "/actuator/health")
                        .permitAll()
                        // security-ack: Prometheus 스크랩 경로. 본문에 JVM 상태·DB 풀 수치·엔드포인트별 URI 가
                        // 그대로 실리므로 공개돼선 안 된다. 방어선은 인증이 아니라 포트 격리다 — actuator 는
                        // 내부 전용 management 포트(9091)에서만 서비스되고 그 포트는 컨테이너 밖으로 매핑하지
                        // 않는다. 그 전제는 ActuatorPortSeparationTest 가 설정 파일 수준에서 고정한다.
                        .requestMatchers(HttpMethod.GET, "/actuator/prometheus")
                        .permitAll()
                        // security-ack: 공개 조회 경로. 와일드카드(`/exchange-rates/**`)를 쓰지 않고 열거하는 것이
                        // 요점이다 — 와일드카드면 컨트롤러에 GET 을 하나 더 붙이는 것만으로 이 파일을 건드리지 않고
                        // 무인증 공개돼 deny-by-default 가 무너진다. 열거해두면 신규 GET 은 기본적으로 401 이 되고,
                        // 공개하려면 여기에 명시해야 해서 PermitAllSnapshotTest 가 그 판단을 강제한다.
                        // `{currencyCode}` 도 한 세그먼트 와일드카드라 같은 구멍이 되므로 통화코드 형태로 제약한다
                        // (소문자는 enum 변환이 어차피 거부하므로 동작하던 요청이 줄지 않는다).
                        .requestMatchers(
                                HttpMethod.GET,
                                "/api/v1/exchange-rates",
                                "/api/v1/exchange-rates/range",
                                "/api/v1/exchange-rates/snapshot",
                                "/api/v1/exchange-rates/status",
                                "/api/v1/exchange-rates/{currencyCode:[A-Z]{3}}",
                                "/api/v1/categories",
                                "/api/v1/assets")
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint((request, response, authException) ->
                                writeErrorResponse(response, objectMapper, ErrorCode.UNAUTHORIZED))
                        .accessDeniedHandler((request, response, accessDeniedException) ->
                                writeErrorResponse(response, objectMapper, ErrorCode.FORBIDDEN)))
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()));
        rateLimitFilterProvider.ifAvailable(
                filter -> http.addFilterBefore(filter, BearerTokenAuthenticationFilter.class));
        return http.build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(
            @Value("${woni.security.cors.allowed-origins}") List<String> allowedOrigins) {
        List<String> sanitizedOrigins = allowedOrigins.stream()
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toList();
        if (sanitizedOrigins.isEmpty() || sanitizedOrigins.stream().anyMatch(origin -> origin.contains("*"))) {
            throw new IllegalStateException("CORS allowed origins must be explicit and must not contain '*'.");
        }

        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(sanitizedOrigins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept"));
        configuration.setMaxAge(Duration.ofHours(1));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    /**
     * JWKS URI 를 직접 받는다 — {@code JwtDecoders.fromIssuerLocation(...)} 은 <b>빈 생성 시점에</b> 디스커버리 문서를
     * 동기로 가져오므로, 그 왕복이 늦으면 컨텍스트 생성이 실패하고 앱이 아예 뜨지 않는다(2026-08-06 운영에서 배포·리부팅에
     * 각 1회 재현 — Supabase {@code /.well-known/openid-configuration} Read timed out). 무인 배포에서는 이것이
     * "정상 이미지가 롤백·낙인되는" 유일한 경로이기도 하다.
     *
     * <p>디스커버리로 얻는 것은 {@code jwks_uri} 하나뿐이고 그 값은 issuer 에서 그대로 파생되므로(설정에서 조립한다),
     * 왕복을 없애도 잃는 정보가 없다. issuer 검증은 {@code configure(...)} 의 {@link JwtIssuerValidator} 가 토큰의
     * {@code iss} 클레임에 대해 그대로 수행하므로 검증 강도도 같다. {@link #jwkSource} 는 지연 조회라 기동 시점에
     * 네트워크를 타지 않는다.
     *
     * <p>알고리즘은 명시해야 한다 — 이 빌더는 기본이 <b>RS256 전용</b>이고(디스커버리 경로와 달리 JWKS 의
     * {@code alg} 를 읽지 않는다), 운영 Supabase JWKS 에는 ES256(EC P-256) 키만 있어서 빼면 모든 실토큰이
     * 거부된다. Supabase 가 서명 알고리즘을 바꾸면 이 줄도 함께 바꿔야 한다.
     *
     * <p>JWKS 조회 계층 자체는 {@link #jwkSource} 가 조립한다 — 그쪽 javadoc 에 이유가 있다.
     */
    @Bean
    @ConditionalOnMissingBean(JwtDecoder.class)
    JwtDecoder jwtDecoder(
            JWKSource<SecurityContext> jwkSource,
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuerUri,
            @Value("${woni.security.jwt.audience}") String audience) {
        return configure(
                NimbusJwtDecoder.withJwkSource(jwkSource)
                        .jwsAlgorithm(SignatureAlgorithm.ES256)
                        .build(),
                issuerUri,
                audience);
    }

    /**
     * JWKS 조회 계층을 직접 조립한다. {@code NimbusJwtDecoder.withJwkSetUri(...)} 의 내장 경로를 쓰지 않는 이유는
     * 그 기본값이 <b>운영에서 간헐 401 을 냈기 때문</b>이다(2026-08-28~09-01 실측 6건 = 앱 요청의 1.1%. 토큰은
     * 멀쩡한데 서버가 공개키를 못 가져와 거부한다). 기본값 셋이 겹쳐서 난 실패다:
     *
     * <ul>
     *   <li>{@code RestTemplateWithNimbusDefaultTimeouts} 가 connect·read 를 <b>각 500ms 로 하드코딩</b>한다 —
     *       설정 프로퍼티로는 못 바꾸므로 {@link DefaultResourceRetriever} 를 직접 준다({@link #JWKS_HTTP_TIMEOUT}).
     *   <li>{@code refreshAheadCache(false)} 라 캐시 TTL(5분)이 끝나는 순간 <b>요청 스레드가 직접 동기 fetch</b> 한다.
     *       하루 288번 그 왕복이 사용자 요청 경로에 실린다. 실측 6건 중 5건이 이 경로였다(배포 직후는 1건뿐이다).
     *   <li>{@code retrying}·{@code outageTolerant} 가 모두 꺼져 있어 <b>1회 실패가 즉시 401</b> 이다.
     * </ul>
     *
     * <p><b>{@code refreshAheadCache(true)} 로는 부족하다 — 반드시 2-arg 오버로드를 써야 한다.</b> boolean 하나짜리는
     * {@code caching} 과 {@code refreshAhead} 만 세팅하고 {@code refreshAheadScheduled} 는 기본값 {@code false} 로
     * 남긴다(nimbus 10.9 바이트코드 확인). 그러면 타이머가 아예 만들어지지 않아 갱신은 <b>"만료 30초 전 창 안에 요청이
     * 들어왔을 때"만</b> 백그라운드로 일어나고, 그 창에 요청이 없으면 만료 직후 첫 요청이 그대로 블로킹 fetch 를 탄다 —
     * 위에서 지운다고 한 바로 그 경로다. {@code scheduled=true} 를 줘야 주기 갱신 타이머가 생겨 요청 경로에서 조회가
     * 사라진다 — 다만 <b>무조건은 아니다</b>. 타이머 재무장은 갱신에 <b>성공</b>했을 때만 일어나고 실패하면 이벤트만
     * 통지된 채(리스너를 달지 않았으므로 로그도 없다) 조용히 멎으므로, 그다음 갱신은 TTL 만료 뒤 첫 요청의 동기
     * fetch 다. 그 경로는 아래 타임아웃·{@code retrying}·{@code outageTolerant} 가 받아낸다.
     *
     * <p>{@code rateLimited} 는 <b>의도적으로 켠 채로 둔다</b>(Nimbus 기본값이지만 Spring 의 내장 경로는 꺼서
     * 델타가 된다). 캐시에 없는 {@code kid} 가 오면 강제 refresh 가 나가는데, 이를 열어두면 임의 {@code kid} 를
     * 흘리는 것만으로 Supabase JWKS 로 요청이 증폭된다. 대가는 키 로테이션 직후 버킷이 비면 새 키 확보가 최대
     * 30초 밀릴 수 있다는 것이다. <b>이 30초는 아래 {@code retrying} 이 흡수하지 못한다</b> —
     * {@code RateLimitReachedException} 은 {@code KeySourceException} 직계라
     * {@code JWKSetUnavailableException} 만 잡는 {@code RetryingJWKSetSource} 의 사정 밖이고, 조립 순서상
     * {@code Retrying} 이 rate limiter 보다 <b>안쪽</b>이라 애초에 그 예외를 볼 수도 없다. 흡수하는 것은 401 을 받고
     * 다시 시도하는 클라이언트뿐이다.
     *
     * <p>여전히 <b>지연 조회</b>다 — 빈 생성 시점에 네트워크를 타지 않으므로 위 문단의 기동 블로킹 사고는 되살아나지
     * 않는다. {@code JWKSourceBuilder.build()} 는 URL 을 파싱만 하고 첫 조회는 첫 토큰 검증 때 일어난다.
     *
     * <p><b>빈으로 분리한 것은 취향이 아니라 필수다.</b> {@code refreshAheadCache(true)} 는 내부에서
     * {@code Executors.newSingleThreadExecutor()} 와 {@code newSingleThreadScheduledExecutor()} 를 ThreadFactory
     * 없이 만든다 — 즉 <b>non-daemon 스레드 2개</b>다(scheduler 는 위의 {@code scheduled=true} 때문에 생긴다.
     * 1-arg 오버로드면 executor 하나뿐이다). 반환 타입({@code JWKSetBasedJWKSource})이 {@code Closeable}
     * 이므로 빈으로 두면 Spring 이 컨텍스트 종료 때 {@code close()} 를 불러 정리하지만, {@link #jwtDecoder} 안에
     * 인라인하면 아무도 닫지 않아 스레드가 샌다.
     *
     * <p>{@code @Lazy} 는 <b>쓰이지 않을 객체와 스레드를 만들지 않기 위한 것</b>이다. 통합 테스트는
     * {@code @MockitoBean JwtDecoder} 로 디코더를 치환하는데, 그러면 {@link #jwtDecoder} 의 팩터리가 호출되지 않아
     * 이 빈을 <b>아무도 참조하지 않게 된다</b>. {@code @Lazy} 가 없으면 참조 여부와 무관하게 싱글턴이 선생성돼
     * 쓰이지도 않을 스레드 2개가 컨텍스트마다 뜬다. 프로덕션에서는 {@link #jwtDecoder} 가 non-lazy 싱글턴이고 이 빈을
     * 파라미터로 주입받으므로 컨텍스트 refresh 시점에 그대로 생성된다 — 첫 요청이 느려지지 않는다.
     *
     * <p>반면 여기에 {@code @ConditionalOnMissingBean(JwtDecoder.class)} 를 <b>같이 걸면 안 된다</b> — 먼저 평가되는
     * {@link #jwtDecoder} 자신이 {@code JwtDecoder} 빈이라 이 빈이 스킵되고, 그 {@link #jwtDecoder} 가 주입받을 대상이
     * 사라져 컨텍스트가 뜨지 않는다.
     */
    @Bean
    @Lazy
    JWKSource<SecurityContext> jwkSource(
            @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String jwkSetUri)
            throws MalformedURLException {
        DefaultResourceRetriever retriever = new DefaultResourceRetriever(
                (int) JWKS_HTTP_TIMEOUT.toMillis(),
                (int) JWKS_HTTP_TIMEOUT.toMillis(),
                JWKSourceBuilder.DEFAULT_HTTP_SIZE_LIMIT);
        return JWKSourceBuilder.<SecurityContext>create(URI.create(jwkSetUri).toURL(), retriever)
                .refreshAheadCache(JWKSourceBuilder.DEFAULT_REFRESH_AHEAD_TIME, true)
                .rateLimited(true)
                .retrying(true)
                .outageTolerant(JWKS_OUTAGE_TOLERANCE.toMillis())
                .build();
    }

    /**
     * 토큰 검증 규칙을 디코더에 배선한다. {@code jwtDecoder()} 는 첫 토큰 검증 때 JWKS 로 네트워크를 타므로, 검증 규칙만
     * 이 메서드로 떼어 테스트가 로컬 키쌍으로 조립한 디코더에 같은 설정을 적용할 수 있게 한다.
     */
    static NimbusJwtDecoder configure(NimbusJwtDecoder decoder, String issuerUri, String audience) {
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(Duration.ofSeconds(60)),
                new JwtIssuerValidator(issuerUri),
                new AudienceValidator(audience)));
        return decoder;
    }

    static void writeErrorResponse(HttpServletResponse response, ObjectMapper objectMapper, ErrorCode errorCode)
            throws IOException {
        response.setStatus(errorCode.getHttpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), ErrorResponse.of(errorCode.getCode(), errorCode.getMessage()));
    }

    private record AudienceValidator(String audience) implements OAuth2TokenValidator<Jwt> {

        private static final OAuth2Error ERROR =
                new OAuth2Error("invalid_token", "The required audience is missing.", null);

        @Override
        public OAuth2TokenValidatorResult validate(Jwt token) {
            // aud 클레임이 없으면 getAudience() 가 null 이다 — null 검사 없이 contains 를 부르면
            // 검증 실패가 아니라 NPE 가 필터 밖으로 나가 401 대신 500 이 된다.
            List<String> tokenAudience = token.getAudience();
            if (tokenAudience != null && tokenAudience.contains(audience)) {
                return OAuth2TokenValidatorResult.success();
            }
            return OAuth2TokenValidatorResult.failure(ERROR);
        }
    }
}

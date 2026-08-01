package com.self.multi_currency_household_ledger.config;

import com.self.multi_currency_household_ledger.common.dto.ErrorResponse;
import com.self.multi_currency_household_ledger.common.exception.ErrorCode;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import tools.jackson.databind.ObjectMapper;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http, CorsConfigurationSource corsConfigurationSource, ObjectMapper objectMapper)
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
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(Duration.ofHours(1));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    @ConditionalOnMissingBean(JwtDecoder.class)
    JwtDecoder jwtDecoder(
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuerUri,
            @Value("${woni.security.jwt.audience}") String audience) {
        return configure(JwtDecoders.fromIssuerLocation(issuerUri), issuerUri, audience);
    }

    /**
     * 토큰 검증 규칙을 디코더에 배선한다. {@code jwtDecoder()} 는 JWKS 를 받아오느라 네트워크를 타므로, 검증 규칙만
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

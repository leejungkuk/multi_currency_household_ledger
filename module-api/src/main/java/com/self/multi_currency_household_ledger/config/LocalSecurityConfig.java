package com.self.multi_currency_household_ledger.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;

/**
 * local 프로파일 전용 보안 체인. 로컬에서 토큰 없이 dev 도구를 쓸 수 있도록 다음만 인증을 면제한다:
 *
 * <ul>
 *   <li>Swagger UI({@code /swagger-ui.html}, {@code /swagger-ui/**}) 와 OpenAPI 문서({@code /v3/api-docs/**})
 *   <li>dev 시드 도구 {@code POST /api/v1/exchange-rates/collect} (시스템 공용 환율, member_id 불요)
 * </ul>
 *
 * <p>{@code @Profile("local")} 이라 배포 프로파일에는 이 빈이 없고, collect 컨트롤러도 local 전용이다. 면제 목록 밖
 * (다른 경로·{@code POST} 외 collect 메서드)은 {@link SecurityConfig} 의 deny-by-default 체인이 그대로 인증을
 * 강제한다({@code @Order(0)} 으로 이 목록만 먼저 가로챈다).
 */
@Configuration
@Profile("local")
public class LocalSecurityConfig {

    static final String COLLECT_PATH = "/api/v1/exchange-rates/collect";

    @Bean
    @Order(0)
    SecurityFilterChain localDevSecurityFilterChain(HttpSecurity http) throws Exception {
        http.securityMatcher(new OrRequestMatcher(
                        AntPathRequestMatcher.antMatcher("/swagger-ui.html"),
                        AntPathRequestMatcher.antMatcher("/swagger-ui/**"),
                        AntPathRequestMatcher.antMatcher("/v3/api-docs/**"),
                        AntPathRequestMatcher.antMatcher(HttpMethod.POST, COLLECT_PATH)))
                // security-ack: local 전용(@Profile) dev 접근만 개방 — Swagger UI/OpenAPI 문서 + 시스템 공용 환율
                // 시드(POST collect, member_id 불요). 배포 프로파일엔 이 빈 없음. 그 외는 deny-by-default 유지.
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
                // security-ack: stateless 토큰리스 dev 호출이라 세션 쿠키 CSRF 불요(메인 체인과 동일 정책). local 전용.
                .csrf(csrf -> csrf.disable());
        return http.build();
    }
}

package com.self.multi_currency_household_ledger.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.util.unit.DataSize;
import tools.jackson.databind.ObjectMapper;

@Configuration
class RequestSizeLimitConfig {

    @Bean
    FilterRegistrationBean<RequestSizeLimitFilter> requestSizeLimitFilterRegistration(
            ObjectMapper objectMapper, @Value("${woni.security.max-request-body-size}") DataSize maxBodySize) {
        FilterRegistrationBean<RequestSizeLimitFilter> registration =
                new FilterRegistrationBean<>(new RequestSizeLimitFilter(maxBodySize.toBytes(), objectMapper));
        // 관측 필터(HIGHEST_PRECEDENCE + 1) 뒤여야 413이 http.server.requests에 집계되고,
        // FormContentFilter(-9900) 앞이어야 인증 전 form-urlencoded 무제한 판독을 차단한다.
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 2);
        return registration;
    }
}

package com.self.multi_currency_household_ledger.config;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

@Configuration
@ConditionalOnProperty(prefix = "woni.security.rate-limit", name = "enabled", matchIfMissing = true)
class RateLimitConfig {

    private static final Duration WINDOW = Duration.ofSeconds(60);
    private static final int MAX_KEYS = 50_000;

    @Bean
    RateLimitFilter rateLimitFilter(
            Clock clock,
            MeterRegistry meterRegistry,
            ObjectMapper objectMapper,
            @Value("${woni.security.rate-limit.read-limit}") int readLimit,
            @Value("${woni.security.rate-limit.write-limit}") int writeLimit) {
        return new RateLimitFilter(clock, meterRegistry, objectMapper, WINDOW, readLimit, writeLimit, MAX_KEYS);
    }

    @Bean
    FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration(RateLimitFilter rateLimitFilter) {
        FilterRegistrationBean<RateLimitFilter> registration = new FilterRegistrationBean<>(rateLimitFilter);
        registration.setEnabled(false);
        return registration;
    }
}

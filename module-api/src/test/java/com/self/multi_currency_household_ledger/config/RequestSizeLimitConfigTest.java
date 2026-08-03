package com.self.multi_currency_household_ledger.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.servlet.filter.OrderedFormContentFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.Ordered;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.unit.DataSize;
import tools.jackson.databind.ObjectMapper;

class RequestSizeLimitConfigTest {

    @Test
    void 관측_필터_뒤이면서_form_content_filter_앞에_등록한다() {
        FilterRegistrationBean<RequestSizeLimitFilter> registration = new RequestSizeLimitConfig()
                .requestSizeLimitFilterRegistration(new ObjectMapper(), DataSize.ofKilobytes(1));

        assertThat(registration.getOrder())
                .as("관측 필터 뒤여야 413이 http.server.requests 메트릭에 집계된다")
                .isGreaterThan(Ordered.HIGHEST_PRECEDENCE + 1);
        assertThat(registration.getOrder())
                .as("FormContentFilter 앞이어야 인증 전 form-urlencoded 무제한 판독을 막는다")
                .isLessThan(OrderedFormContentFilter.DEFAULT_ORDER);
    }

    @Test
    void application_yml은_환경변수와_1536KB_기본값_문자열을_고정한다() throws IOException {
        assertThat(property("woni.security.max-request-body-size")).isEqualTo("${WONI_MAX_REQUEST_BODY_SIZE:1536KB}");
    }

    private static Object property(String key) throws IOException {
        List<PropertySource<?>> sources =
                new YamlPropertySourceLoader().load("application.yml", new ClassPathResource("application.yml"));
        return sources.getFirst().getProperty(key);
    }
}

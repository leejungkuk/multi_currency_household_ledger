package com.self.multi_currency_household_ledger.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CacheControlHeadersTest {

    @Test
    @DisplayName("공개 read Cache-Control 값은 public·max-age 1시간으로 고정된다")
    void public_read_value_is_pinned() {
        assertThat(CacheControlHeaders.PUBLIC_READ).isEqualTo("public, max-age=3600");
    }
}

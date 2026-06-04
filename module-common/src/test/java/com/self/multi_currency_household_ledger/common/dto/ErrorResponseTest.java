package com.self.multi_currency_household_ledger.common.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ErrorResponseTest {

    @Test
    void of는_code와_message와_timestamp를_담는다() {
        ErrorResponse response = ErrorResponse.of("INVALID_DATE", "미래 날짜는 조회할 수 없습니다.");

        assertThat(response.code()).isEqualTo("INVALID_DATE");
        assertThat(response.message()).isEqualTo("미래 날짜는 조회할 수 없습니다.");
        assertThat(response.timestamp()).isNotNull();
    }
}

package com.self.multi_currency_household_ledger.common.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ApiResponseTest {

    @Test
    @DisplayName("success(data)는 data와 timestamp를 담는다")
    void success_with_data() {
        ApiResponse<String> response = ApiResponse.success("hello");

        assertThat(response.success()).isTrue();
        assertThat(response.code()).isNull();
        assertThat(response.data()).isEqualTo("hello");
        assertThat(response.message()).isNull();
        assertThat(response.timestamp()).isNotNull();
    }
}

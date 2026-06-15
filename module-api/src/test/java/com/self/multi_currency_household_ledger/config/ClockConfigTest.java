package com.self.multi_currency_household_ledger.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ClockConfigTest {

    @Test
    @DisplayName("KST 기준 Clock 빈을 생성한다")
    void creates_kst_clock() {
        Clock clock = new ClockConfig().kstClock();

        assertThat(clock.getZone()).isEqualTo(ZoneId.of("Asia/Seoul"));
    }
}

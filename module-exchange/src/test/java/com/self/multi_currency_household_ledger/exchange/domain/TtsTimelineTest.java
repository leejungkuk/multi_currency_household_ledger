package com.self.multi_currency_household_ledger.exchange.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TtsTimelineTest {

    private static final LocalDate FRI = LocalDate.of(2026, 9, 4);
    private static final LocalDate MON = LocalDate.of(2026, 9, 7);

    private final TtsTimeline timeline = TtsTimeline.of(List.of(
            ExchangeRate.of(CurrencyCode.USD, new BigDecimal("1300.00"), FRI),
            ExchangeRate.of(CurrencyCode.USD, new BigDecimal("1310.00"), MON)));

    @Test
    @DisplayName("첫 환율보다 이른 날짜는 비어 있다 — 가장 오래된 값으로 폴백하지 않는다")
    void empty_before_first_rate() {
        assertThat(timeline.onOrBefore(FRI.minusDays(1))).isEqualTo(Optional.empty());
    }

    @Test
    @DisplayName("환율 기준일 당일은 그날 tts 다")
    void exact_date() {
        assertThat(timeline.onOrBefore(FRI)).contains(new BigDecimal("1300.00"));
        assertThat(timeline.onOrBefore(MON)).contains(new BigDecimal("1310.00"));
    }

    @Test
    @DisplayName("주말 공백은 직전 영업일 tts 를 받는다")
    void weekend_gap_takes_previous() {
        assertThat(timeline.onOrBefore(FRI.plusDays(1))).contains(new BigDecimal("1300.00"));
        assertThat(timeline.onOrBefore(FRI.plusDays(2))).contains(new BigDecimal("1300.00"));
    }

    @Test
    @DisplayName("마지막 환율 이후 날짜는 가장 최근 tts 를 받는다")
    void after_last_takes_latest() {
        assertThat(timeline.onOrBefore(MON.plusDays(365))).contains(new BigDecimal("1310.00"));
    }

    @Test
    @DisplayName("행이 없으면 어떤 날짜도 비어 있다")
    void empty_timeline() {
        assertThat(TtsTimeline.of(List.of()).onOrBefore(MON)).isEmpty();
    }
}

package com.self.multi_currency_household_ledger.exchange.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;

/** 한 통화의 기준일별 tts. 날짜 이하 가장 최근 값만 돌려주고, 없으면 비운다(폴백 없음). */
public final class TtsTimeline {

    private final NavigableMap<LocalDate, BigDecimal> ttsByDate;

    private TtsTimeline(NavigableMap<LocalDate, BigDecimal> ttsByDate) {
        this.ttsByDate = ttsByDate;
    }

    public static TtsTimeline of(Collection<ExchangeRate> rates) {
        NavigableMap<LocalDate, BigDecimal> ttsByDate = new TreeMap<>();
        rates.forEach(rate -> ttsByDate.put(rate.getBaseDate(), rate.getTts()));
        return new TtsTimeline(ttsByDate);
    }

    public Optional<BigDecimal> onOrBefore(LocalDate date) {
        return Optional.ofNullable(ttsByDate.floorEntry(date)).map(Map.Entry::getValue);
    }
}

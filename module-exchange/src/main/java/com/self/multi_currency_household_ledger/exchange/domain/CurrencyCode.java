package com.self.multi_currency_household_ledger.exchange.domain;

import java.util.Arrays;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum CurrencyCode {

    USD("USD", 1, "미 달러"),
    EUR("EUR", 1, "유로"),
    JPY("JPY(100)", 100, "일본 엔"),
    CNY("CNY", 1, "중국 위안"),
    GBP("GBP", 1, "영국 파운드");

    private final String apiCode;
    private final int unit;
    private final String displayName;

    public static CurrencyCode fromCode(String code) {
        return Arrays.stream(values())
                .filter(c -> c.apiCode.equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("지원하지 않는 통화 코드: " + code));
    }
}

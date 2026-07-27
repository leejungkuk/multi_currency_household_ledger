package com.self.multi_currency_household_ledger.exchange.domain;

import com.self.multi_currency_household_ledger.common.exception.BusinessException;
import com.self.multi_currency_household_ledger.exchange.exception.ExchangeErrorCode;
import java.util.Arrays;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum CurrencyCode {
    KRW("KRW", 1, "대한민국 원"),
    USD("USD", 1, "미 달러"),
    EUR("EUR", 1, "유로"),
    JPY("JPY(100)", 100, "일본 엔"),
    // EximBank AP01은 위안화를 cur_unit=CNH(역외 위안)로만 제공한다.
    // apiCode는 EximBank 매핑 전용이며 wire/DB 값은 enum name "CNY" 그대로다: CNY(wire/DB) ← CNH(EximBank).
    CNY("CNH", 1, "중국 위안"),
    GBP("GBP", 1, "영국 파운드"),
    THB("THB", 1, "태국 바트"),
    HKD("HKD", 1, "홍콩 달러"),
    SGD("SGD", 1, "싱가포르 달러"),
    IDR("IDR(100)", 100, "인도네시아 루피아"),
    MYR("MYR", 1, "말레이시아 링깃"),
    AUD("AUD", 1, "호주 달러"),
    NZD("NZD", 1, "뉴질랜드 달러");

    private final String apiCode;
    private final int unit;
    private final String displayName;

    public boolean isBase() {
        return this == KRW;
    }

    public static CurrencyCode fromCode(String code) {
        return Arrays.stream(values())
                .filter(c -> c.apiCode.equals(code))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ExchangeErrorCode.UNSUPPORTED_CURRENCY));
    }
}

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
    CNY("CNY", 1, "중국 위안"),
    GBP("GBP", 1, "영국 파운드");

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

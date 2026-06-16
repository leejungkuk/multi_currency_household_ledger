package com.self.multi_currency_household_ledger.exchange.domain;

import com.self.multi_currency_household_ledger.common.entity.BaseEntity;
import com.self.multi_currency_household_ledger.common.exception.BusinessException;
import com.self.multi_currency_household_ledger.exchange.exception.ExchangeErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "exchange_rate", uniqueConstraints = @UniqueConstraint(columnNames = {"currency_code", "base_date"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ExchangeRate extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private CurrencyCode currencyCode;

    @Column(nullable = false, precision = 19, scale = 6)
    private BigDecimal tts;

    @Column(nullable = false)
    private LocalDate baseDate;

    private ExchangeRate(CurrencyCode currencyCode, BigDecimal tts, LocalDate baseDate) {
        this.currencyCode = currencyCode;
        this.tts = tts;
        this.baseDate = baseDate;
    }

    public static ExchangeRate of(CurrencyCode currencyCode, BigDecimal tts, LocalDate baseDate) {
        return new ExchangeRate(currencyCode, tts, baseDate);
    }

    /** 미래 날짜에는 환율이 존재할 수 없으므로 조회 전 도메인 레벨에서 차단한다. */
    public static void assertNotFuture(LocalDate date, Clock clock) {
        if (date.isAfter(LocalDate.now(clock))) {
            throw new BusinessException(ExchangeErrorCode.INVALID_DATE);
        }
    }

    /**
     * 외화 → KRW 변환
     */
    public BigDecimal convertToKrw(BigDecimal foreignAmount) {
        return foreignAmount
                .divide(BigDecimal.valueOf(currencyCode.getUnit()), 10, RoundingMode.HALF_UP)
                .multiply(tts)
                .setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * KRW → 외화 변환
     */
    public BigDecimal convertFromKrw(BigDecimal krwAmount) {
        return krwAmount
                .divide(tts, 10, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(currencyCode.getUnit()))
                .setScale(2, RoundingMode.HALF_UP);
    }
}

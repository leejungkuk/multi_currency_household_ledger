package com.self.multi_currency_household_ledger.exchange.domain;

import com.self.multi_currency_household_ledger.common.entity.BaseEntity;
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
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(
        name = "exchange_rate",
        uniqueConstraints = @UniqueConstraint(columnNames = {"currency_code", "base_date"})
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ExchangeRate extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CurrencyCode currencyCode;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal dealBasRate;

    @Column(nullable = false)
    private LocalDate baseDate;

    private ExchangeRate(CurrencyCode currencyCode, BigDecimal dealBasRate, LocalDate baseDate) {
        this.currencyCode = currencyCode;
        this.dealBasRate = dealBasRate;
        this.baseDate = baseDate;
    }

    public static ExchangeRate of(CurrencyCode currencyCode, BigDecimal dealBasRate, LocalDate baseDate) {
        return new ExchangeRate(currencyCode, dealBasRate, baseDate);
    }

    /**
     * 외화 → KRW 변환
     */
    public BigDecimal convertToKrw(BigDecimal foreignAmount) {
        return foreignAmount
                .divide(BigDecimal.valueOf(currencyCode.getUnit()), 10, RoundingMode.HALF_UP)
                .multiply(dealBasRate)
                .setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * KRW → 외화 변환
     */
    public BigDecimal convertFromKrw(BigDecimal krwAmount) {
        return krwAmount
                .divide(dealBasRate, 10, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(currencyCode.getUnit()))
                .setScale(2, RoundingMode.HALF_UP);
    }
}

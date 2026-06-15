package com.self.multi_currency_household_ledger.ledger.domain;

import com.self.multi_currency_household_ledger.common.entity.BaseEntity;
import com.self.multi_currency_household_ledger.common.exception.BusinessException;
import com.self.multi_currency_household_ledger.exchange.domain.CurrencyCode;
import com.self.multi_currency_household_ledger.exchange.domain.ExchangeRate;
import com.self.multi_currency_household_ledger.ledger.exception.LedgerErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@Table(name = "ledger_entry")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LedgerEntry extends BaseEntity {

    private static final BigDecimal MAX_ORIGINAL_AMOUNT = new BigDecimal("99999999.00");
    private static final int MAX_ORIGINAL_AMOUNT_SCALE = 2;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private UUID memberId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private TransactionType transactionType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "asset_id", nullable = false)
    private Asset asset;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal originalAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private CurrencyCode currencyCode;

    @Column(nullable = false, precision = 19, scale = 6)
    private BigDecimal appliedRate;

    @Column
    private LocalDate rateBaseDate;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal krwAmount;

    @Column(nullable = false)
    private LocalDate transactionDate;

    @Column(length = 255)
    private String memo;

    private LedgerEntry(
            UUID memberId,
            Category category,
            Asset asset,
            BigDecimal originalAmount,
            CurrencyCode currencyCode,
            LocalDate transactionDate,
            String memo,
            BigDecimal appliedRate,
            LocalDate rateBaseDate,
            BigDecimal krwAmount) {
        this.memberId = memberId;
        this.transactionType = category.getTransactionType();
        this.category = category;
        this.asset = asset;
        this.originalAmount = originalAmount;
        this.currencyCode = currencyCode;
        this.transactionDate = transactionDate;
        this.memo = memo;
        this.appliedRate = appliedRate;
        this.rateBaseDate = rateBaseDate;
        this.krwAmount = krwAmount;
    }

    public static LedgerEntry of(
            UUID memberId,
            Category category,
            Asset asset,
            BigDecimal originalAmount,
            CurrencyCode currencyCode,
            LocalDate transactionDate,
            String memo,
            ExchangeRate exchangeRate,
            Clock clock) {
        assertValidOriginalAmount(originalAmount);
        assertFutureDateOnlyKrw(currencyCode, transactionDate, clock);

        BigDecimal appliedRate;
        LocalDate rateBaseDate;
        BigDecimal krwAmount;

        if (currencyCode.isBase()) {
            appliedRate = BigDecimal.ONE;
            rateBaseDate = null;
            krwAmount = originalAmount;
        } else {
            // 외화 거래에는 서비스가 항상 비-null ExchangeRate를 주입한다. null은 호출자 프로그래밍 오류.
            Objects.requireNonNull(exchangeRate, "외화 거래에는 ExchangeRate가 필요합니다.");
            appliedRate = exchangeRate.getTts();
            rateBaseDate = exchangeRate.getBaseDate();
            krwAmount = exchangeRate.convertToKrw(originalAmount);
        }

        return new LedgerEntry(
                memberId,
                category,
                asset,
                originalAmount,
                currencyCode,
                transactionDate,
                memo,
                appliedRate,
                rateBaseDate,
                krwAmount);
    }

    public boolean recalculate(BigDecimal newRate, LocalDate newBaseDate) {
        if (currencyCode.isBase() || !usesOlderRateThan(newBaseDate)) {
            return false;
        }

        this.appliedRate = Objects.requireNonNull(newRate, "newRate must not be null");
        this.rateBaseDate = Objects.requireNonNull(newBaseDate, "newBaseDate must not be null");
        this.krwAmount = convertToKrw(originalAmount, newRate);
        return true;
    }

    public boolean usesOlderRateThan(LocalDate applicableBaseDate) {
        if (currencyCode.isBase()) {
            return false;
        }
        return rateBaseDate == null || rateBaseDate.isBefore(applicableBaseDate);
    }

    private static void assertValidOriginalAmount(BigDecimal amount) {
        if (amount == null
                || amount.compareTo(BigDecimal.ZERO) <= 0
                || amount.compareTo(MAX_ORIGINAL_AMOUNT) > 0
                || amount.scale() > MAX_ORIGINAL_AMOUNT_SCALE) {
            throw new BusinessException(LedgerErrorCode.INVALID_AMOUNT);
        }
    }

    private static void assertFutureDateOnlyKrw(CurrencyCode currencyCode, LocalDate transactionDate, Clock clock) {
        if (!currencyCode.isBase() && transactionDate.isAfter(LocalDate.now(clock))) {
            throw new BusinessException(LedgerErrorCode.INVALID_FUTURE_DATE);
        }
    }

    private BigDecimal convertToKrw(BigDecimal foreignAmount, BigDecimal rate) {
        return foreignAmount
                .divide(BigDecimal.valueOf(currencyCode.getUnit()), 10, RoundingMode.HALF_UP)
                .multiply(rate)
                .setScale(2, RoundingMode.HALF_UP);
    }
}

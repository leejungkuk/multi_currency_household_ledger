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
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@Table(name = "ledger_entry")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LedgerEntry extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long memberId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private TransactionType transactionType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "asset_id", nullable = false)
    private Asset asset;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal originalAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private CurrencyCode currencyCode;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal appliedRate;

    @Column
    private LocalDate rateBaseDate;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal krwAmount;

    @Column(nullable = false)
    private LocalDate transactionDate;

    @Column(length = 255)
    private String memo;

    private LedgerEntry(
            Long memberId,
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
            Long memberId,
            Category category,
            Asset asset,
            BigDecimal originalAmount,
            CurrencyCode currencyCode,
            LocalDate transactionDate,
            String memo,
            ExchangeRate exchangeRate) {
        assertAmountPositive(originalAmount);
        assertFutureDateOnlyKrw(currencyCode, transactionDate);

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
            appliedRate = exchangeRate.getDealBasRate();
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

    private static void assertAmountPositive(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(LedgerErrorCode.INVALID_AMOUNT);
        }
    }

    private static void assertFutureDateOnlyKrw(CurrencyCode currencyCode, LocalDate transactionDate) {
        // "오늘" 판정은 서버 TZ 와 무관하게 KST 기준 (프론트 계약과 일치)
        if (!currencyCode.isBase() && transactionDate.isAfter(LocalDate.now(ZoneId.of("Asia/Seoul")))) {
            throw new BusinessException(LedgerErrorCode.INVALID_FUTURE_DATE);
        }
    }
}

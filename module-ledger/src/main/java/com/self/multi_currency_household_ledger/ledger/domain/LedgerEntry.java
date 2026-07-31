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
import jakarta.persistence.Version;
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

    @Column(name = "client_entry_id")
    private UUID clientEntryId;

    @Column(name = "client_payload_hash", length = 64)
    private String clientPayloadHash;

    /** 재계산 배치와 회원 수정이 같은 행을 동시에 고칠 때 나중 커밋이 앞선 수정을 되돌리지 않게 한다. */
    @Version
    @Column(nullable = false)
    private long version;

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
        AmountSnapshot amountSnapshot = calculateAmountSnapshot(originalAmount, currencyCode, exchangeRate);

        return new LedgerEntry(
                memberId,
                category,
                asset,
                originalAmount,
                currencyCode,
                transactionDate,
                normalizeMemo(memo),
                amountSnapshot.appliedRate(),
                amountSnapshot.rateBaseDate(),
                amountSnapshot.krwAmount());
    }

    public void replace(
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
        AmountSnapshot amountSnapshot = calculateAmountSnapshot(originalAmount, currencyCode, exchangeRate);

        this.transactionType = category.getTransactionType();
        this.category = category;
        this.asset = asset;
        this.originalAmount = originalAmount;
        this.currencyCode = currencyCode;
        this.transactionDate = transactionDate;
        this.memo = normalizeMemo(memo);
        this.appliedRate = amountSnapshot.appliedRate();
        this.rateBaseDate = amountSnapshot.rateBaseDate();
        this.krwAmount = amountSnapshot.krwAmount();
        // clientEntryId 는 유지한다 — 끊으면 같은 식별자의 재push 가 새 행을 만들어 거래가 중복된다.
        // 해시는 지운다 — 서버 값이 클라이언트 payload 와 달라졌으므로 옛 payload 재전송은 충돌로 드러나야 한다.
        // 단 이 보호는 해시를 선행조건으로 보는 import 경로에만 걸린다. sync 는 요청에 해시가 없어(last-writer-wins)
        // 스테일 payload 재push 가 여기서 반영한 수정을 조용히 덮어쓴다.
        this.clientPayloadHash = null;
    }

    public boolean recalculate(BigDecimal newRate, LocalDate newBaseDate) {
        if (currencyCode.isBase() || !usesOlderRateThan(newBaseDate)) {
            return false;
        }

        this.appliedRate = Objects.requireNonNull(newRate, "newRate must not be null");
        this.rateBaseDate = Objects.requireNonNull(newBaseDate, "newBaseDate must not be null");
        this.krwAmount = convertToKrw(originalAmount, currencyCode, newRate);
        return true;
    }

    public boolean usesOlderRateThan(LocalDate applicableBaseDate) {
        if (currencyCode.isBase()) {
            return false;
        }
        return rateBaseDate == null || rateBaseDate.isBefore(applicableBaseDate);
    }

    public void assignClientEntry(UUID clientEntryId, String clientPayloadHash) {
        Objects.requireNonNull(clientEntryId, "clientEntryId must not be null");
        Objects.requireNonNull(clientPayloadHash, "clientPayloadHash must not be null");
        if (clientPayloadHash.length() != 64) {
            throw new IllegalArgumentException("clientPayloadHash must be 64 characters");
        }

        this.clientEntryId = clientEntryId;
        this.clientPayloadHash = clientPayloadHash;
    }

    public void assignClientEntry(UUID clientEntryId) {
        this.clientEntryId = Objects.requireNonNull(clientEntryId, "clientEntryId must not be null");
        this.clientPayloadHash = null;
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

    public static String normalizeMemo(String memo) {
        if (memo == null) {
            return null;
        }

        String stripped = memo.strip();
        return stripped.isEmpty() ? null : stripped;
    }

    private static AmountSnapshot calculateAmountSnapshot(
            BigDecimal originalAmount, CurrencyCode currencyCode, ExchangeRate exchangeRate) {
        if (currencyCode.isBase()) {
            return new AmountSnapshot(BigDecimal.ONE, null, originalAmount);
        }

        // 외화 거래에는 서비스가 항상 비-null ExchangeRate를 주입한다. null은 호출자 프로그래밍 오류.
        Objects.requireNonNull(exchangeRate, "외화 거래에는 ExchangeRate가 필요합니다.");
        BigDecimal appliedRate = exchangeRate.getTts();
        LocalDate rateBaseDate = exchangeRate.getBaseDate();
        BigDecimal krwAmount = convertToKrw(originalAmount, currencyCode, appliedRate);
        return new AmountSnapshot(appliedRate, rateBaseDate, krwAmount);
    }

    private static BigDecimal convertToKrw(BigDecimal foreignAmount, CurrencyCode currencyCode, BigDecimal rate) {
        return foreignAmount
                .divide(BigDecimal.valueOf(currencyCode.getUnit()), 10, RoundingMode.HALF_UP)
                .multiply(rate)
                .setScale(2, RoundingMode.HALF_UP);
    }

    private record AmountSnapshot(BigDecimal appliedRate, LocalDate rateBaseDate, BigDecimal krwAmount) {}
}

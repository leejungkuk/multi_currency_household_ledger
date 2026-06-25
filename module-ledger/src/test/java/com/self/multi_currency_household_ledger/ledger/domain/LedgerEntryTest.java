package com.self.multi_currency_household_ledger.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.self.multi_currency_household_ledger.common.exception.BusinessException;
import com.self.multi_currency_household_ledger.exchange.domain.CurrencyCode;
import com.self.multi_currency_household_ledger.exchange.domain.ExchangeRate;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LedgerEntryTest {

    private static final UUID MEMBER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final LocalDate TODAY = LocalDate.of(2026, 4, 6);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-04-05T15:00:00Z"), KST);

    private Category category;
    private Asset asset;

    @BeforeEach
    void setUp() {
        category = new Category(TransactionType.EXPENSE, "FOOD_DINING", "식비", "Food & Dining", "🍽️", 1);
        asset = new Asset("CASH", "현금", "Cash", 3);
    }

    // 정상적으로 가계부 내역이 생성되는지 확인한다.
    @Test
    @DisplayName("정상적인 데이터로 가계부 내역을 생성할 수 있다")
    void create_ledger_entry_success() {
        ExchangeRate exchangeRate = ExchangeRate.of(CurrencyCode.USD, BigDecimal.valueOf(1300), TODAY);
        LedgerEntry entry = LedgerEntry.of(
                MEMBER_ID,
                category,
                asset,
                BigDecimal.valueOf(100),
                CurrencyCode.USD,
                TODAY,
                "점심 식사",
                exchangeRate,
                FIXED_CLOCK);

        assertThat(entry.getOriginalAmount()).isEqualByComparingTo(BigDecimal.valueOf(100));
        assertThat(entry.getMemberId()).isEqualTo(MEMBER_ID);
        assertThat(entry.getKrwAmount()).isEqualByComparingTo(BigDecimal.valueOf(130000));
        assertThat(entry.getTransactionType()).isEqualTo(TransactionType.EXPENSE);
    }

    @Test
    @DisplayName("가계부 내역 생성 시 메모를 정규화한다")
    void create_normalizes_memo() {
        assertThat(createKrwEntry("  hi  ").getMemo()).isEqualTo("hi");
        assertThat(createKrwEntry("   ").getMemo()).isNull();
        assertThat(createKrwEntry(null).getMemo()).isNull();
        assertThat(createKrwEntry("점심").getMemo()).isEqualTo("점심");
    }

    @Test
    @DisplayName("import 클라이언트 식별자는 도메인 메서드로 부여한다")
    void assign_client_entry() {
        UUID clientEntryId = UUID.fromString("10000000-0000-0000-0000-000000000001");
        String payloadHash = "a".repeat(64);
        LedgerEntry entry = createKrwEntry("커피");

        entry.assignClientEntry(clientEntryId, payloadHash);

        assertThat(entry.getClientEntryId()).isEqualTo(clientEntryId);
        assertThat(entry.getClientPayloadHash()).isEqualTo(payloadHash);
    }

    // 금액이 0 이하일 경우 예외가 발생하는지 확인한다.
    @Test
    @DisplayName("금액이 0 이하일 경우 예외가 발생한다")
    void create_ledger_entry_fails_when_amount_is_zero_or_negative() {
        ExchangeRate exchangeRate = ExchangeRate.of(CurrencyCode.USD, BigDecimal.valueOf(1300), TODAY);
        assertThatThrownBy(() -> LedgerEntry.of(
                        MEMBER_ID,
                        category,
                        asset,
                        BigDecimal.ZERO,
                        CurrencyCode.USD,
                        TODAY,
                        "점심 식사",
                        exchangeRate,
                        FIXED_CLOCK))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("금액이 99,999,999를 초과하면 예외가 발생한다")
    void create_ledger_entry_fails_when_amount_exceeds_maximum() {
        assertThatThrownBy(() -> LedgerEntry.of(
                        MEMBER_ID,
                        category,
                        asset,
                        new BigDecimal("100000000.00"),
                        CurrencyCode.KRW,
                        TODAY,
                        "큰 금액",
                        null,
                        FIXED_CLOCK))
                .isInstanceOf(BusinessException.class);
    }

    // 외화 거래 시 미래 날짜로 생성할 수 없는지 확인한다.
    @Test
    @DisplayName("외화 거래의 경우 미래 날짜로 생성할 수 없다")
    void create_ledger_entry_fails_when_future_date_for_foreign_currency() {
        ExchangeRate exchangeRate = ExchangeRate.of(CurrencyCode.USD, BigDecimal.valueOf(1300), TODAY);
        assertThatThrownBy(() -> LedgerEntry.of(
                        MEMBER_ID,
                        category,
                        asset,
                        BigDecimal.valueOf(100),
                        CurrencyCode.USD,
                        TODAY.plusDays(1),
                        "점심 식사",
                        exchangeRate,
                        FIXED_CLOCK))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("외화 거래 재계산은 환율, 기준일, 원화 금액을 함께 갱신한다")
    void recalculate_foreign_currency_updates_rate_snapshot_together() {
        ExchangeRate oldRate = ExchangeRate.of(CurrencyCode.USD, new BigDecimal("1300.000000"), TODAY.minusDays(1));
        LedgerEntry entry = LedgerEntry.of(
                MEMBER_ID,
                category,
                asset,
                new BigDecimal("100.00"),
                CurrencyCode.USD,
                TODAY,
                "점심 식사",
                oldRate,
                FIXED_CLOCK);

        boolean recalculated = entry.recalculate(new BigDecimal("1320.000000"), TODAY);

        assertThat(recalculated).isTrue();
        assertThat(entry.getAppliedRate()).isEqualByComparingTo(new BigDecimal("1320.000000"));
        assertThat(entry.getRateBaseDate()).isEqualTo(TODAY);
        assertThat(entry.getKrwAmount()).isEqualByComparingTo(new BigDecimal("132000.00"));
    }

    @Test
    @DisplayName("이미 적용 가능한 최신 기준일을 쓰는 외화 거래는 재계산하지 않는다")
    void recalculate_foreign_currency_is_noop_when_base_date_is_current() {
        ExchangeRate currentRate = ExchangeRate.of(CurrencyCode.USD, new BigDecimal("1320.000000"), TODAY);
        LedgerEntry entry = LedgerEntry.of(
                MEMBER_ID,
                category,
                asset,
                new BigDecimal("100.00"),
                CurrencyCode.USD,
                TODAY,
                "점심 식사",
                currentRate,
                FIXED_CLOCK);

        boolean recalculated = entry.recalculate(new BigDecimal("1320.000000"), TODAY);

        assertThat(recalculated).isFalse();
        assertThat(entry.getAppliedRate()).isEqualByComparingTo(new BigDecimal("1320.000000"));
        assertThat(entry.getRateBaseDate()).isEqualTo(TODAY);
        assertThat(entry.getKrwAmount()).isEqualByComparingTo(new BigDecimal("132000.00"));
    }

    @Test
    @DisplayName("KRW 거래는 재계산 요청이 와도 불변이다")
    void recalculate_krw_entry_is_noop() {
        LedgerEntry entry = LedgerEntry.of(
                MEMBER_ID,
                category,
                asset,
                new BigDecimal("5000.00"),
                CurrencyCode.KRW,
                TODAY,
                "커피",
                null,
                FIXED_CLOCK);

        boolean recalculated = entry.recalculate(new BigDecimal("1320.000000"), TODAY);

        assertThat(recalculated).isFalse();
        assertThat(entry.getAppliedRate()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(entry.getRateBaseDate()).isNull();
        assertThat(entry.getKrwAmount()).isEqualByComparingTo(new BigDecimal("5000.00"));
    }

    @Test
    @DisplayName("외화 거래 교체는 카테고리 거래 유형과 환율 스냅샷, 원화 금액을 다시 계산한다")
    void replace_foreign_currency_recalculates_rate_snapshot_and_transaction_type() {
        ExchangeRate oldRate = ExchangeRate.of(CurrencyCode.USD, new BigDecimal("1300.000000"), TODAY.minusDays(1));
        LedgerEntry entry = LedgerEntry.of(
                MEMBER_ID,
                category,
                asset,
                new BigDecimal("100.00"),
                CurrencyCode.USD,
                TODAY,
                "기존 메모",
                oldRate,
                FIXED_CLOCK);
        Category incomeCategory = new Category(TransactionType.INCOME, "SALARY", "급여", "Salary", "💼", 1);
        Asset card = new Asset("CREDIT_CARD", "신용카드", "Credit Card", 1);
        ExchangeRate newRate = ExchangeRate.of(CurrencyCode.EUR, new BigDecimal("1400.000000"), TODAY);

        entry.replace(
                incomeCategory, card, new BigDecimal("50.00"), CurrencyCode.EUR, TODAY, "수정 메모", newRate, FIXED_CLOCK);

        assertThat(entry.getTransactionType()).isEqualTo(TransactionType.INCOME);
        assertThat(entry.getCategory()).isSameAs(incomeCategory);
        assertThat(entry.getAsset()).isSameAs(card);
        assertThat(entry.getOriginalAmount()).isEqualByComparingTo(new BigDecimal("50.00"));
        assertThat(entry.getCurrencyCode()).isEqualTo(CurrencyCode.EUR);
        assertThat(entry.getAppliedRate()).isEqualByComparingTo(new BigDecimal("1400.000000"));
        assertThat(entry.getRateBaseDate()).isEqualTo(TODAY);
        assertThat(entry.getKrwAmount()).isEqualByComparingTo(new BigDecimal("70000.00"));
        assertThat(entry.getMemo()).isEqualTo("수정 메모");
    }

    @Test
    @DisplayName("가계부 내역 교체 시 메모를 정규화한다")
    void replace_normalizes_memo() {
        assertThat(replaceMemo("  hi  ")).isEqualTo("hi");
        assertThat(replaceMemo("   ")).isNull();
        assertThat(replaceMemo(null)).isNull();
        assertThat(replaceMemo("점심")).isEqualTo("점심");
    }

    @Test
    @DisplayName("KRW 거래 교체는 환율 스냅샷을 1과 null로 재설정하고 원금 그대로 원화 금액에 반영한다")
    void replace_krw_resets_rate_snapshot() {
        ExchangeRate oldRate = ExchangeRate.of(CurrencyCode.USD, new BigDecimal("1300.000000"), TODAY.minusDays(1));
        LedgerEntry entry = LedgerEntry.of(
                MEMBER_ID,
                category,
                asset,
                new BigDecimal("100.00"),
                CurrencyCode.USD,
                TODAY,
                "기존 메모",
                oldRate,
                FIXED_CLOCK);

        entry.replace(
                category,
                asset,
                new BigDecimal("5000.00"),
                CurrencyCode.KRW,
                TODAY.plusDays(1),
                null,
                null,
                FIXED_CLOCK);

        assertThat(entry.getAppliedRate()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(entry.getRateBaseDate()).isNull();
        assertThat(entry.getKrwAmount()).isEqualByComparingTo(new BigDecimal("5000.00"));
        assertThat(entry.getTransactionDate()).isEqualTo(TODAY.plusDays(1));
        assertThat(entry.getMemo()).isNull();
    }

    @Test
    @DisplayName("가계부 내역 교체 시 import 클라이언트 식별자를 클리어한다")
    void replace_clears_client_import_identity() {
        UUID clientEntryId = UUID.fromString("10000000-0000-0000-0000-000000000002");
        LedgerEntry entry = createKrwEntry("import 거래");
        entry.assignClientEntry(clientEntryId, "a".repeat(64));

        entry.replace(category, asset, new BigDecimal("6000.00"), CurrencyCode.KRW, TODAY, "사용자 수정", null, FIXED_CLOCK);

        assertThat(entry.getClientEntryId()).isNull();
        assertThat(entry.getClientPayloadHash()).isNull();
    }

    private LedgerEntry createKrwEntry(String memo) {
        return LedgerEntry.of(
                MEMBER_ID,
                category,
                asset,
                new BigDecimal("5000.00"),
                CurrencyCode.KRW,
                TODAY,
                memo,
                null,
                FIXED_CLOCK);
    }

    private String replaceMemo(String memo) {
        LedgerEntry entry = createKrwEntry("기존 메모");

        entry.replace(category, asset, new BigDecimal("5000.00"), CurrencyCode.KRW, TODAY, memo, null, FIXED_CLOCK);

        return entry.getMemo();
    }
}

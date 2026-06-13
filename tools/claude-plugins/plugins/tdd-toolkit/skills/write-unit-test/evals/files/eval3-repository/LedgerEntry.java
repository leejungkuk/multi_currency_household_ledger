package com.self.multi_currency_household_ledger.ledger.entity;

import com.self.multi_currency_household_ledger.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "ledger_entries")
public class LedgerEntry extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long memberId;

    @Column(nullable = false)
    private String currency;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false)
    private LocalDate spentAt;

    protected LedgerEntry() {
    }

    private LedgerEntry(Long memberId, String currency, BigDecimal amount, LocalDate spentAt) {
        this.memberId = memberId;
        this.currency = currency;
        this.amount = amount;
        this.spentAt = spentAt;
    }

    public static LedgerEntry of(Long memberId, String currency, BigDecimal amount, LocalDate spentAt) {
        return new LedgerEntry(memberId, currency, amount, spentAt);
    }

    public Long getId() {
        return id;
    }

    public Long getMemberId() {
        return memberId;
    }

    public String getCurrency() {
        return currency;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public LocalDate getSpentAt() {
        return spentAt;
    }
}

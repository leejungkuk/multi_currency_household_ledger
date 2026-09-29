package com.self.multi_currency_household_ledger.ledger.domain;

import com.self.multi_currency_household_ledger.common.entity.BaseEntity;
import com.self.multi_currency_household_ledger.exchange.domain.CurrencyCode;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@Table(name = "budget")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Budget extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private UUID memberId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private TransactionType axis;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private BudgetKind kind;

    /** 그 달의 1일. DEFAULT 는 적용 시작 달, MONTH 는 그 달이다. */
    @Column(nullable = false)
    private LocalDate month;

    /** currencyCode·totalAmount 가 둘 다 null 이면 끔이다. */
    @Enumerated(EnumType.STRING)
    @Column(length = 3)
    private CurrencyCode currencyCode;

    @Column(precision = 19, scale = 2)
    private BigDecimal totalAmount;

    @OneToMany(mappedBy = "budget", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<BudgetAllocation> allocations = new ArrayList<>();

    public Budget(
            UUID memberId,
            TransactionType axis,
            BudgetKind kind,
            YearMonth month,
            CurrencyCode currencyCode,
            BigDecimal totalAmount,
            List<BudgetAllocation> allocations) {
        this.memberId = memberId;
        this.axis = axis;
        this.kind = kind;
        this.month = month.atDay(1);
        this.currencyCode = currencyCode;
        this.totalAmount = totalAmount;
        for (BudgetAllocation allocation : allocations) {
            allocation.attachTo(this);
            this.allocations.add(allocation);
        }
    }
}

package com.self.multi_currency_household_ledger.ledger.domain;

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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 예산 몫. 결제수단 그룹과 카테고리 중 정확히 하나를 가리킨다(ck_budget_allocation_target). */
@Entity
@Getter
@Table(name = "budget_allocation")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BudgetAllocation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "budget_id", nullable = false)
    private Budget budget;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private PaymentGroup paymentGroup;

    @Column(name = "category_id")
    private Long categoryId;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    private BudgetAllocation(PaymentGroup paymentGroup, Long categoryId, BigDecimal amount) {
        this.paymentGroup = paymentGroup;
        this.categoryId = categoryId;
        this.amount = amount;
    }

    public static BudgetAllocation forPaymentGroup(PaymentGroup paymentGroup, BigDecimal amount) {
        return new BudgetAllocation(paymentGroup, null, amount);
    }

    public static BudgetAllocation forCategory(Long categoryId, BigDecimal amount) {
        return new BudgetAllocation(null, categoryId, amount);
    }

    void attachTo(Budget budget) {
        this.budget = budget;
    }

    void changeAmount(BigDecimal amount) {
        this.amount = amount;
    }
}

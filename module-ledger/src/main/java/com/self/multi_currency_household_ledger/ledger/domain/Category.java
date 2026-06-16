package com.self.multi_currency_household_ledger.ledger.domain;

import com.self.multi_currency_household_ledger.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@Table(name = "category")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Category extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private TransactionType transactionType;

    @Column(nullable = false, length = 50)
    private String code;

    @Column(nullable = false, length = 100)
    private String displayNameKo;

    @Column(nullable = false, length = 100)
    private String displayNameEn;

    @Column(length = 20)
    private String icon;

    @Column(nullable = false)
    private int sortOrder;

    @Column(nullable = false)
    private boolean isActive;

    public Category(
            TransactionType transactionType,
            String code,
            String displayNameKo,
            String displayNameEn,
            String icon,
            int sortOrder) {
        this.transactionType = transactionType;
        this.code = code;
        this.displayNameKo = displayNameKo;
        this.displayNameEn = displayNameEn;
        this.icon = icon;
        this.sortOrder = sortOrder;
        this.isActive = true;
    }
}

package com.self.multi_currency_household_ledger.ledger.domain;

import com.self.multi_currency_household_ledger.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@Table(name = "asset")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Asset extends BaseEntity {

    public static final Long SYSTEM_OWNER_ID = 0L;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String code;

    @Column(nullable = false, length = 100)
    private String displayName;

    @Column(length = 20)
    private String icon;

    @Column(nullable = false)
    private int sortOrder;

    @Column(nullable = false)
    private Long ownerMemberId;

    @Column(nullable = false)
    private boolean isActive;

    public Asset(String code, String displayName, String icon, int sortOrder, Long ownerMemberId) {
        this.code = code;
        this.displayName = displayName;
        this.icon = icon;
        this.sortOrder = sortOrder;
        this.ownerMemberId = ownerMemberId;
        this.isActive = true;
    }
}

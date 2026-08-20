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
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.DynamicUpdate;

@Entity
@Getter
@Table(name = "category")
// 전컬럼 UPDATE면 수정 트랜잭션이 들고 있던 옛 is_active 스냅샷이 동시 삭제 커밋을 덮어써 삭제된 행이 되살아난다.
@DynamicUpdate
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Category extends BaseEntity {

    /** 회원 한 명이 가질 수 있는 활성 커스텀 카테고리 수. 재정렬 요청 길이 상한도 같은 값이다. */
    public static final int CUSTOM_LIMIT = 100;

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

    @Column(name = "owner_member_id")
    private UUID ownerMemberId;

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

    public static Category custom(UUID ownerMemberId, TransactionType type, String name, String icon) {
        Category category = new Category(type, "CUSTOM", name, name, icon, 1000);
        category.ownerMemberId = ownerMemberId;
        return category;
    }

    public void rename(String name, String icon) {
        this.displayNameKo = name;
        this.displayNameEn = name;
        this.icon = icon;
    }

    public void applySortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }

    public void deactivate() {
        this.isActive = false;
    }
}

package com.self.multi_currency_household_ledger.common.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import java.time.LocalDateTime;
import java.time.ZoneId;
import lombok.Getter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Getter
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseEntity {

    @CreatedDate
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;

    /**
     * 자기 컬럼이 그대로여도 이 행을 더럽혀 {@code @LastModifiedDate} 가 돌게 한다 — 자식 행만 바뀐 저장에서도 수정 시각이 남아야 할 때 쓴다. 값은
     * auditing 과 같은 JVM 기본 존이다.
     */
    protected void markModified() {
        this.updatedAt = LocalDateTime.now(ZoneId.systemDefault());
    }
}

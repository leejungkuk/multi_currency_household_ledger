package com.self.multi_currency_household_ledger.ledger.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CategoryRepository extends JpaRepository<Category, Long> {

    List<Category> findByOwnerMemberIdIsNullAndTransactionTypeAndIsActiveTrueOrderBySortOrder(TransactionType type);

    List<Category> findByOwnerMemberIdAndTransactionTypeAndIsActiveTrueOrderBySortOrderAscIdDesc(
            UUID ownerMemberId, TransactionType type);

    long countByOwnerMemberIdAndIsActiveTrue(UUID ownerMemberId);

    Optional<Category> findByIdAndOwnerMemberId(Long id, UUID ownerMemberId);

    Optional<Category> findByIdAndOwnerMemberIdAndIsActiveTrue(Long id, UUID ownerMemberId);

    @Query(
            "select c from Category c where c.id = :id and c.isActive = true and (c.ownerMemberId is null or c.ownerMemberId = :memberId)")
    Optional<Category> findUsableCategory(@Param("id") Long id, @Param("memberId") UUID memberId);

    // purge 전용. 비활성(soft delete) 잔재도 함께 지워야 "내 데이터 삭제"가 절반만 되지 않으므로 isActive 를 술어에 넣지 않는다.
    // 시스템 카테고리는 owner_member_id 가 null 이라 등가 비교에 정의상 매칭되지 않는다.
    // JPQL bulk delete 는 영속성 컨텍스트를 우회하므로, 같은 트랜잭션에 관리 중인 Category 가 있으면 flush 로
    // 선반영하고 삭제 후 컨텍스트를 비워 stale 엔티티를 남기지 않는다.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from Category c where c.ownerMemberId = :ownerMemberId")
    int deleteAllByOwnerMemberId(@Param("ownerMemberId") UUID ownerMemberId);
}

package com.self.multi_currency_household_ledger.ledger.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CategoryRepository extends JpaRepository<Category, Long> {

    List<Category> findByOwnerMemberIdIsNullAndTransactionTypeAndIsActiveTrueOrderBySortOrder(TransactionType type);

    List<Category> findByOwnerMemberIdAndTransactionTypeAndIsActiveTrueOrderById(
            UUID ownerMemberId, TransactionType type);

    long countByOwnerMemberIdAndIsActiveTrue(UUID ownerMemberId);

    Optional<Category> findByIdAndOwnerMemberId(Long id, UUID ownerMemberId);

    Optional<Category> findByIdAndOwnerMemberIdAndIsActiveTrue(Long id, UUID ownerMemberId);

    @Query(
            "select c from Category c where c.id = :id and c.isActive = true and (c.ownerMemberId is null or c.ownerMemberId = :memberId)")
    Optional<Category> findUsableCategory(@Param("id") Long id, @Param("memberId") UUID memberId);
}

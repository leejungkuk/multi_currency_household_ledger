package com.self.multi_currency_household_ledger.ledger.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CategoryRepository extends JpaRepository<Category, Long> {

    Optional<Category> findByIdAndOwnerMemberIdIn(Long id, List<Long> ownerMemberIds);

    List<Category> findByTransactionTypeAndOwnerMemberIdInAndIsActiveTrueOrderBySortOrder(
            TransactionType type, List<Long> ownerMemberIds);

    boolean existsByOwnerMemberIdAndTransactionTypeAndCode(
            Long ownerMemberId, TransactionType transactionType, String code);
}

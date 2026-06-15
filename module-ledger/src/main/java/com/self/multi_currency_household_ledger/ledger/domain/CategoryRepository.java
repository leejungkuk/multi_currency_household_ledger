package com.self.multi_currency_household_ledger.ledger.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CategoryRepository extends JpaRepository<Category, Long> {

    Optional<Category> findByIdAndOwnerMemberId(Long id, Long ownerMemberId);

    List<Category> findByTransactionTypeAndOwnerMemberIdAndIsActiveTrueOrderBySortOrder(
            TransactionType type, Long ownerMemberId);

    boolean existsByOwnerMemberIdAndTransactionTypeAndCode(
            Long ownerMemberId, TransactionType transactionType, String code);
}

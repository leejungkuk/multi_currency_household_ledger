package com.self.multi_currency_household_ledger.ledger.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CategoryRepository extends JpaRepository<Category, Long> {

    List<Category> findByTransactionTypeAndIsActiveTrueOrderBySortOrder(TransactionType type);
}

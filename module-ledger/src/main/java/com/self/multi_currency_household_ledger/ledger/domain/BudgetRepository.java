package com.self.multi_currency_household_ledger.ledger.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BudgetRepository extends JpaRepository<Budget, Long> {

    // 달 M 해석용 — 두 축의 (MONTH, M) 와 (DEFAULT, ≤M) 를 몫과 함께 한 번에 읽는다.
    @Query(
            """
            select distinct b from Budget b left join fetch b.allocations
            where b.memberId = :memberId
              and ((b.kind = com.self.multi_currency_household_ledger.ledger.domain.BudgetKind.MONTH and b.month = :month)
                or (b.kind = com.self.multi_currency_household_ledger.ledger.domain.BudgetKind.DEFAULT and b.month <= :month))
            """)
    List<Budget> findForMonth(@Param("memberId") UUID memberId, @Param("month") LocalDate month);

    Optional<Budget> findByMemberIdAndAxisAndKindAndMonth(
            UUID memberId, TransactionType axis, BudgetKind kind, LocalDate month);

    // 몫은 budget_id 의 DB cascade 로 지워진다 — JPQL bulk delete 는 JPA cascade 를 거치지 않는다.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
            """
            delete from Budget b
            where b.memberId = :memberId
              and b.axis = :axis
              and b.kind = com.self.multi_currency_household_ledger.ledger.domain.BudgetKind.DEFAULT
              and b.month > :month
            """)
    int deleteDefaultsAfter(
            @Param("memberId") UUID memberId, @Param("axis") TransactionType axis, @Param("month") LocalDate month);

    boolean existsByMemberIdAndTotalAmountIsNotNull(UUID memberId);

    // purge 전용. 몫은 budget_id 의 DB cascade 로 지워진다.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from Budget b where b.memberId = :memberId")
    int deleteAllByMemberId(@Param("memberId") UUID memberId);
}

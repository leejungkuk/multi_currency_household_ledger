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

    Optional<Budget> findFirstByMemberIdAndAxisAndKindAndMonthLessThanOrderByMonthDesc(
            UUID memberId, TransactionType axis, BudgetKind kind, LocalDate month);

    // 카테고리 삭제 전용 — budget_allocation 에는 member_id 가 없어 회원 예산 행으로 좁힌다.
    // clear 하지 않는다: 호출자(CatalogService) 트랜잭션이 들고 있는 영속 엔티티를 분리하지 않는다.
    @Modifying(flushAutomatically = true)
    @Query(
            """
            delete from BudgetAllocation a
            where a.categoryId = :categoryId
              and a.budget.id in (select b.id from Budget b where b.memberId = :memberId and b.month >= :month)
            """)
    int deleteCategoryAllocationsFrom(
            @Param("memberId") UUID memberId, @Param("categoryId") Long categoryId, @Param("month") LocalDate month);

    boolean existsByMemberIdAndTotalAmountIsNotNull(UUID memberId);

    // 예산 쓰기 경로의 회원 단위 직렬화(DESIGN §2). 트랜잭션이 끝나면 풀린다. THIS_MONTH·FROM_THIS_MONTH 는 서로 다른 행을
    // 쓰므로 행 락이나 @Version 으로는 잡히지 않는다.
    @Query(
            value = "select 1 from pg_advisory_xact_lock(hashtext('budget:' || cast(:memberId as text)))",
            nativeQuery = true)
    int lockMember(@Param("memberId") UUID memberId);

    // purge 전용. 몫은 budget_id 의 DB cascade 로 지워진다.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from Budget b where b.memberId = :memberId")
    int deleteAllByMemberId(@Param("memberId") UUID memberId);
}

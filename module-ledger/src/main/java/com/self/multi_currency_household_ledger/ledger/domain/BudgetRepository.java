package com.self.multi_currency_household_ledger.ledger.domain;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BudgetRepository extends JpaRepository<Budget, Long> {

    // 그 회원·그 달의 행을 몫과 함께 한 번에 읽는다.
    @Query(
            """
            select distinct b from Budget b left join fetch b.allocations
            where b.memberId = :memberId and b.month = :month
            """)
    Optional<Budget> findByMemberIdAndMonth(@Param("memberId") UUID memberId, @Param("month") LocalDate month);

    boolean existsByMemberId(UUID memberId);

    // 예산 쓰기 경로의 회원 단위 직렬화(DESIGN §7). 트랜잭션이 끝나면 풀린다. 행이 없는 달의 동시 insert 는 행 락이나
    // @Version 으로는 잡히지 않아 uk_budget 위반이 된다.
    @Query(
            value = "select 1 from pg_advisory_xact_lock(hashtext('budget:' || cast(:memberId as text)))",
            nativeQuery = true)
    int lockMember(@Param("memberId") UUID memberId);

    // purge 전용. 몫은 budget_id 의 DB cascade 로 지워진다.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from Budget b where b.memberId = :memberId")
    int deleteAllByMemberId(@Param("memberId") UUID memberId);
}

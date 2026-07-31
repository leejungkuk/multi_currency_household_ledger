package com.self.multi_currency_household_ledger.ledger.domain;

import com.self.multi_currency_household_ledger.exchange.domain.CurrencyCode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {

    Optional<LedgerEntry> findByIdAndMemberId(Long id, UUID memberId);

    long countByMemberId(UUID memberId);

    Optional<LedgerEntry> findByMemberIdAndClientEntryId(UUID memberId, UUID clientEntryId);

    // import 배치가 쓰는 유일한 조회. 항목마다 findByMemberIdAndClientEntryId 를 돌리면 요청 1건이 항목 수만큼
    // 왕복하며 커넥션을 점유한다(풀 크기가 작아 동시 몇 건이면 전 요청이 대기한다). 한 번에 읽어 두면
    // 쿼터 판정("새로 생길 행 수")과 기존 행 조회를 같은 결과로 함께 처리할 수 있다.
    List<LedgerEntry> findByMemberIdAndClientEntryIdIn(UUID memberId, Collection<UUID> clientEntryIds);

    // JPQL bulk delete 는 영속성 컨텍스트를 우회하므로, 같은 트랜잭션에 관리 중인 LedgerEntry 가 있으면
    // flush 로 선반영하고 삭제 후 컨텍스트를 비워 stale 엔티티를 남기지 않는다(향후 재사용 대비 방어).
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
            """
            delete from LedgerEntry entry
            where entry.memberId = :memberId
              and entry.clientEntryId = :clientEntryId
            """)
    int deleteByMemberIdAndClientEntryId(@Param("memberId") UUID memberId, @Param("clientEntryId") UUID clientEntryId);

    @Query(
            """
            select coalesce(sum(entry.krwAmount), 0)
            from LedgerEntry entry
            where entry.memberId = :memberId
              and entry.transactionType = :transactionType
              and entry.transactionDate >= :startDate
              and entry.transactionDate < :endDate
            """)
    BigDecimal sumKrwAmountByMemberIdAndTransactionTypeAndTransactionDateRange(
            @Param("memberId") UUID memberId,
            @Param("transactionType") TransactionType transactionType,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    @Query(
            """
            select entry.currencyCode as currencyCode,
                   entry.transactionType as transactionType,
                   sum(entry.originalAmount) as originalAmount,
                   sum(entry.krwAmount) as krwAmount
            from LedgerEntry entry
            where entry.memberId = :memberId
              and entry.transactionDate >= :startDate
              and entry.transactionDate < :endDate
            group by entry.currencyCode, entry.transactionType
            order by entry.currencyCode asc, entry.transactionType asc
            """)
    List<CurrencySubtotalProjection> findCurrencySubtotalsByMemberIdAndTransactionDateRange(
            @Param("memberId") UUID memberId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    @Query(
            """
            select category.id as categoryId,
                   category.transactionType as transactionType,
                   category.code as categoryCode,
                   category.displayNameKo as categoryDisplayNameKo,
                   category.displayNameEn as categoryDisplayNameEn,
                   category.icon as categoryIcon,
                   category.sortOrder as categorySortOrder,
                   sum(entry.krwAmount) as krwAmount
            from LedgerEntry entry
            join entry.category category
            where entry.memberId = :memberId
              and entry.transactionDate >= :startDate
              and entry.transactionDate < :endDate
            group by category.id,
                     category.transactionType,
                     category.code,
                     category.displayNameKo,
                     category.displayNameEn,
                     category.icon,
                     category.sortOrder
            order by category.sortOrder asc, category.id asc
            """)
    List<CategorySubtotalProjection> findCategorySubtotalsByMemberIdAndTransactionDateRange(
            @Param("memberId") UUID memberId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    List<LedgerEntry>
            findByMemberIdAndTransactionDateGreaterThanEqualAndTransactionDateLessThanOrderByTransactionDateDescIdDesc(
                    UUID memberId, LocalDate startDate, LocalDate endDate, Pageable pageable);

    @Query(
            """
            select entry
            from LedgerEntry entry
            join fetch entry.category
            join fetch entry.asset
            where entry.memberId = :memberId
            order by entry.transactionDate desc, entry.id desc
            """)
    List<LedgerEntry> findRestoreFirstPageByMemberId(@Param("memberId") UUID memberId, Pageable pageable);

    @Query(
            """
            select entry
            from LedgerEntry entry
            join fetch entry.category
            join fetch entry.asset
            where entry.memberId = :memberId
              and (
                  entry.transactionDate < :cursorDate
                  or (entry.transactionDate = :cursorDate and entry.id < :cursorId)
              )
            order by entry.transactionDate desc, entry.id desc
            """)
    List<LedgerEntry> findRestorePageByMemberIdAfterCursor(
            @Param("memberId") UUID memberId,
            @Param("cursorDate") LocalDate cursorDate,
            @Param("cursorId") Long cursorId,
            Pageable pageable);

    @Query(
            """
            select entry
            from LedgerEntry entry
            join fetch entry.category
            join fetch entry.asset
            where entry.memberId = :memberId
            order by entry.updatedAt asc, entry.id asc
            """)
    List<LedgerEntry> findChangesFirstPageByMemberId(@Param("memberId") UUID memberId, Pageable pageable);

    @Query(
            """
            select entry
            from LedgerEntry entry
            join fetch entry.category
            join fetch entry.asset
            where entry.memberId = :memberId
              and (
                  entry.updatedAt > :cursorUpdatedAt
                  or (entry.updatedAt = :cursorUpdatedAt and entry.id > :cursorId)
              )
            order by entry.updatedAt asc, entry.id asc
            """)
    List<LedgerEntry> findChangesPageByMemberIdAfterCursor(
            @Param("memberId") UUID memberId,
            @Param("cursorUpdatedAt") LocalDateTime cursorUpdatedAt,
            @Param("cursorId") Long cursorId,
            Pageable pageable);

    /** rate_base_date 가 비어 있는 행을 "가장 오래된 환율을 쓰는 행"으로 취급하는 센티널(모든 실제 base_date 보다 과거). */
    LocalDate NULL_RATE_BASE_DATE = LocalDate.of(1, 1, 1);

    // 재계산 대상 = "그 거래일에 적용 가능한 최신 tts(= ExchangeRateService.getRateOnOrBefore 의 base_date)보다
    // 오래된 환율을 쓰는 외화 거래". 이 조건을 SQL 에 두면 재계산된 행이 스스로 결과집합에서 빠지므로,
    // 한 주기 상한에 걸려 남은 몫은 커서를 저장하지 않아도 다음 주기에 그대로 다시 잡힌다(초과분 영구 동결 방지).
    // rate_base_date 는 nullable 이고 도메인(LedgerEntry.usesOlderRateThan)은 null 을 "재계산 필요"로 본다.
    // NULL < x 는 NULL 이라 술어에서 그냥 비교하면 그 행이 영구 제외되므로 coalesce 로 센티널을 씌운다.
    // 거래일에 적용 가능한 환율이 아예 없으면(과거 거래를 가장 오래된 환율로 clamp 한 경우) 서브쿼리가 null 이라
    // 대상에서 빠진다 — 갱신할 더 최신 환율이 없으므로 정상이다(포함하면 getRateOnOrBefore 가 던져 배치가 죽는다).
    // 커서 (transaction_date, id) 는 재계산이 갱신하지 않는 컬럼이라 청크 사이에 순서가 흔들리지 않는다.
    @Query(
            """
            select entry
            from LedgerEntry entry
            where entry.currencyCode <> :baseCurrency
              and (
                  entry.transactionDate > :cursorDate
                  or (entry.transactionDate = :cursorDate and entry.id > :cursorId)
              )
              and coalesce(entry.rateBaseDate, :nullRateBaseDate) < (
                  select max(rate.baseDate)
                  from ExchangeRate rate
                  where rate.currencyCode = entry.currencyCode
                    and rate.baseDate <= entry.transactionDate
              )
            order by entry.transactionDate asc, entry.id asc
            """)
    List<LedgerEntry> findStaleForeignEntriesAfterCursor(
            @Param("baseCurrency") CurrencyCode baseCurrency,
            @Param("nullRateBaseDate") LocalDate nullRateBaseDate,
            @Param("cursorDate") LocalDate cursorDate,
            @Param("cursorId") Long cursorId,
            Pageable pageable);

    /** 배치 호출부용 오버로드 — 기준 통화와 null 센티널을 채운다. 커서 초기값은 (보정창 시작일, 모든 id 보다 작은 하한) 이다. */
    default List<LedgerEntry> findStaleForeignEntriesAfterCursor(
            LocalDate cursorDate, Long cursorId, Pageable pageable) {
        return findStaleForeignEntriesAfterCursor(
                CurrencyCode.KRW, NULL_RATE_BASE_DATE, cursorDate, cursorId, pageable);
    }

    interface CurrencySubtotalProjection {

        CurrencyCode getCurrencyCode();

        TransactionType getTransactionType();

        BigDecimal getOriginalAmount();

        BigDecimal getKrwAmount();
    }

    interface CategorySubtotalProjection {

        Long getCategoryId();

        TransactionType getTransactionType();

        String getCategoryCode();

        String getCategoryDisplayNameKo();

        String getCategoryDisplayNameEn();

        String getCategoryIcon();

        Integer getCategorySortOrder();

        BigDecimal getKrwAmount();
    }
}

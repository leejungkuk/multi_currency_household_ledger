package com.self.multi_currency_household_ledger.ledger.domain;

import com.self.multi_currency_household_ledger.exchange.domain.CurrencyCode;
import java.math.BigDecimal;
import java.time.LocalDate;
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

    Optional<LedgerEntry> findByMemberIdAndClientEntryId(UUID memberId, UUID clientEntryId);

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

    List<LedgerEntry> findByCurrencyCodeNotAndTransactionDateGreaterThanEqualOrderByTransactionDateAscIdAsc(
            CurrencyCode currencyCode, LocalDate transactionDate);

    default List<LedgerEntry> findForeignEntriesOnOrAfter(LocalDate transactionDate) {
        return findByCurrencyCodeNotAndTransactionDateGreaterThanEqualOrderByTransactionDateAscIdAsc(
                CurrencyCode.KRW, transactionDate);
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

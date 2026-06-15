package com.self.multi_currency_household_ledger.ledger.repository;

import com.self.multi_currency_household_ledger.ledger.entity.LedgerEntry;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {

    /** 특정 회원의 기간 내 지출 내역을 최신순으로 조회한다. */
    List<LedgerEntry> findByMemberIdAndSpentAtBetweenOrderBySpentAtDesc(
            Long memberId, LocalDate start, LocalDate end);

    /** 특정 회원의 특정 통화 지출 내역을 조회한다. */
    List<LedgerEntry> findByMemberIdAndCurrency(Long memberId, String currency);
}

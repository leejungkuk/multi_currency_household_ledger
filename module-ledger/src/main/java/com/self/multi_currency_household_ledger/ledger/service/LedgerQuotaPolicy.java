package com.self.multi_currency_household_ledger.ledger.service;

import com.self.multi_currency_household_ledger.common.exception.BusinessException;
import com.self.multi_currency_household_ledger.ledger.domain.LedgerEntryRepository;
import com.self.multi_currency_household_ledger.ledger.exception.LedgerErrorCode;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 회원별 저장 행수 상한. 익명 가입이 무료라 <b>레이트 리밋으로는 막을 수 없는 축</b>이다 — 공격자는 한도 안의 속도로
 * 계속 쌓기만 하면 되고, 계정을 새로 파는 비용도 0 이다. 디스크가 소진되면 전 회원이 함께 멈춘다.
 *
 * <p>요청당 count 쿼리 1회만 쓴다(항목마다 세지 않는다).
 */
@Component
public class LedgerQuotaPolicy {

    private final LedgerEntryRepository ledgerEntryRepository;
    private final int maxEntriesPerMember;

    public LedgerQuotaPolicy(
            LedgerEntryRepository ledgerEntryRepository,
            @Value("${ledger.quota.max-entries-per-member:100000}") int maxEntriesPerMember) {
        if (maxEntriesPerMember <= 0) {
            throw new IllegalArgumentException("maxEntriesPerMember must be positive");
        }
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.maxEntriesPerMember = maxEntriesPerMember;
    }

    /**
     * {@code newEntries} 건을 새로 만들어도 한도 안인지 확인한다.
     *
     * @param newEntries 이 요청에서 <b>새로 생길</b> 행 수. import 처럼 기존 행 갱신이 섞이는 경로는 갱신분까지 세면
     *     한도 근처에서 멱등 재요청이 거부되므로, 호출자가 신규 생성분만 넘겨야 한다.
     */
    public void assertCanCreate(UUID memberId, int newEntries) {
        if (newEntries <= 0) {
            return;
        }
        if (ledgerEntryRepository.countByMemberId(memberId) + newEntries > maxEntriesPerMember) {
            throw new BusinessException(LedgerErrorCode.LEDGER_QUOTA_EXCEEDED);
        }
    }
}

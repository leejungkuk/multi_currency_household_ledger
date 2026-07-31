package com.self.multi_currency_household_ledger.ledger.service;

import com.self.multi_currency_household_ledger.exchange.domain.CurrencyCode;
import com.self.multi_currency_household_ledger.exchange.domain.ExchangeRate;
import com.self.multi_currency_household_ledger.exchange.service.ExchangeRateService;
import com.self.multi_currency_household_ledger.ledger.domain.LedgerEntry;
import com.self.multi_currency_household_ledger.ledger.domain.LedgerEntryRepository;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 재계산 배치의 청크 하나를 독립 트랜잭션으로 처리한다.
 *
 * <p>배치 루프({@link LedgerRecalculationService})와 <b>다른 빈</b>이어야 한다 — 같은 빈 안에서 {@code @Transactional}
 * 메서드를 호출하면 프록시를 지나지 않아 전 청크가 한 트랜잭션으로 합쳐진다. 정상 경로는 결과가 같아 드러나지 않고
 * 실패 경로에서만 갈리므로, 부분 커밋을
 * {@code LedgerRecalculationIntegrationTest#earlier_chunk_stays_committed_when_a_later_chunk_fails} 가 고정한다.
 */
@Service
@RequiredArgsConstructor
public class LedgerRecalculationChunkProcessor {

    private final LedgerEntryRepository ledgerEntryRepository;
    private final ExchangeRateService exchangeRateService;

    @Transactional
    public ChunkResult recalculateChunk(LocalDate cursorDate, Long cursorId, int chunkSize) {
        List<LedgerEntry> entries = ledgerEntryRepository.findStaleForeignEntriesAfterCursor(
                cursorDate, cursorId, PageRequest.of(0, chunkSize));

        Map<RateKey, ExchangeRate> rates = new HashMap<>();
        int recalculated = 0;
        for (LedgerEntry entry : entries) {
            if (entry.getCurrencyCode().isBase()) {
                continue;
            }
            // 같은 (통화, 거래일)의 적용 환율은 하나뿐이라 조합당 한 번만 조회한다(거래 건수만큼 왕복하지 않는다).
            ExchangeRate rate = rates.computeIfAbsent(
                    new RateKey(entry.getCurrencyCode(), entry.getTransactionDate()),
                    key -> exchangeRateService.getRateOnOrBefore(key.currencyCode(), key.transactionDate()));
            if (entry.recalculate(rate.getTts(), rate.getBaseDate())) {
                recalculated++;
            }
        }

        if (entries.size() < chunkSize) {
            return new ChunkResult(recalculated, null, null);
        }
        LedgerEntry last = entries.getLast();
        return new ChunkResult(recalculated, last.getTransactionDate(), last.getId());
    }

    public record ChunkResult(int recalculated, LocalDate nextCursorDate, Long nextCursorId) {

        public boolean hasMore() {
            return nextCursorId != null;
        }
    }

    private record RateKey(CurrencyCode currencyCode, LocalDate transactionDate) {}
}

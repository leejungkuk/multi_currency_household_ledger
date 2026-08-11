package com.self.multi_currency_household_ledger.ledger.service;

import com.self.multi_currency_household_ledger.ledger.service.LedgerRecalculationChunkProcessor.ChunkResult;
import java.time.LocalDate;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class LedgerRecalculationService {

    /** 어떤 id 보다도 작은 커서 하한 — (보정창 시작일, 이 값) 이 id 채번 방식과 무관하게 보정창 첫 행을 연다. */
    private static final long CURSOR_ID_LOWER_BOUND = Long.MIN_VALUE;

    private final LedgerRecalculationChunkProcessor chunkProcessor;
    private final int chunkSize;
    private final int maxEntriesPerRun;

    public LedgerRecalculationService(
            LedgerRecalculationChunkProcessor chunkProcessor,
            @Value("${ledger.recalculation.chunk-size:200}") int chunkSize,
            @Value("${ledger.recalculation.max-entries-per-run:5000}") int maxEntriesPerRun) {
        validateSettings(chunkSize, maxEntriesPerRun);
        this.chunkProcessor = chunkProcessor;
        this.chunkSize = chunkSize;
        this.maxEntriesPerRun = maxEntriesPerRun;
    }

    /**
     * 보정창 안에서 "거래일에 적용 가능한 최신 tts 보다 오래된 환율을 쓰는" 외화 거래를 청크 단위로 재계산한다.
     * 트랜잭션 경계는 청크에 있다 — 여기에 {@code @Transactional} 을 붙이면 전 청크가 한 트랜잭션으로 합쳐져
     * 유계화가 무의미해진다.
     *
     * <p>주기 상한에 걸리면 남은 몫은 다음 주기로 넘기고 정상 종료한다(예외 아님). 대상 조건이 SQL 술어라
     * 갱신된 행만 결과집합에서 빠지므로 나머지는 다음 주기가 그대로 이어받는다.
     */
    public int recalculateForeignEntriesFrom(LocalDate windowStart) {
        LocalDate cursorDate = windowStart;
        Long cursorId = CURSOR_ID_LOWER_BOUND;

        int recalculated = 0;
        try {
            while (recalculated < maxEntriesPerRun) {
                Optional<ChunkResult> result = recalculateChunkRetryingOnConflict(
                        cursorDate, cursorId, Math.min(chunkSize, maxEntriesPerRun - recalculated));
                if (result.isEmpty()) {
                    return recalculated;
                }
                ChunkResult chunk = result.get();
                recalculated += chunk.recalculated();
                if (!chunk.hasMore()) {
                    return recalculated;
                }
                cursorDate = chunk.nextCursorDate();
                cursorId = chunk.nextCursorId();
            }
        } catch (RuntimeException e) {
            // 앞 청크는 이미 커밋됐다 — 어디까지 처리했는지 남기고 예외는 그대로 올린다(부분 커밋을 숨기지 않는다).
            log.error(
                    "거래 재계산 청크 실패. 재계산 {}건까지 커밋 후 중단합니다. cursorDate={}, cursorId={}",
                    recalculated,
                    cursorDate,
                    cursorId,
                    e);
            throw e;
        }

        // 상한 도달이 이어지면 미처리분이 보정창 밖으로 밀려 영구 동결된다 — 관측되어야 하는 신호라 warn 이다.
        // 마지막 청크가 가득 찼는지로는 잔여 유무를 알 수 없으므로(LIMIT 페이징의 한계) "남았다"고 단정하지 않는다.
        // 잔여를 세려면 쿼리가 한 번 더 필요한데, 이 로그 하나 때문에 주기마다 그 비용을 낼 이유는 없다.
        log.warn(
                "재계산 주기 상한 {}건에 도달해 이번 주기를 종료합니다(잔여가 있으면 다음 주기가 이어받습니다)."
                        + " 이 로그가 반복되면 미처리분이 시작일 {}의 보정 범위를 벗어나 영구 동결되므로"
                        + " ledger.recalculation.max-entries-per-run 상향을 검토하세요."
                        + " cursorDate={}, cursorId={}",
                maxEntriesPerRun,
                windowStart,
                cursorDate,
                cursorId);
        return recalculated;
    }

    /**
     * 낙관적 락 충돌은 회원이 같은 행을 동시에 고친 정상 경합이라 실패로 다루지 않는다. 충돌한 청크는 통째로
     * 롤백되고 다음 커서도 잃으므로(커서 없이 진행하면 무한 루프) <b>같은 커서로 한 번만</b> 재시도한다.
     * 재시도도 충돌하면 빈 값을 돌려 이번 주기를 정상 종료한다 — 대상 조건이 상태 기반 술어라 남은 몫은
     * 다음 주기가 그대로 이어받는다. 낙관적 락 외의 실패는 잡지 않고 호출자로 전파한다.
     */
    private Optional<ChunkResult> recalculateChunkRetryingOnConflict(LocalDate cursorDate, Long cursorId, int limit) {
        try {
            return Optional.of(chunkProcessor.recalculateChunk(cursorDate, cursorId, limit));
        } catch (OptimisticLockingFailureException firstConflict) {
            log.info(
                    "재계산 청크가 회원 수정과 경합해 같은 커서로 재시도합니다. cursorDate={}, cursorId={}, cause={}",
                    cursorDate,
                    cursorId,
                    firstConflict.getMessage());
        }

        try {
            return Optional.of(chunkProcessor.recalculateChunk(cursorDate, cursorId, limit));
        } catch (OptimisticLockingFailureException retryConflict) {
            log.warn(
                    "재계산 청크가 회원 수정과 연속 경합해 이번 주기를 종료합니다(다음 주기가 이어받습니다). cursorDate={}, cursorId={}",
                    cursorDate,
                    cursorId,
                    retryConflict);
            return Optional.empty();
        }
    }

    private void validateSettings(int chunkSize, int maxEntriesPerRun) {
        if (chunkSize < 1 || maxEntriesPerRun < 1) {
            throw new IllegalArgumentException("chunkSize and maxEntriesPerRun must be positive");
        }
    }
}

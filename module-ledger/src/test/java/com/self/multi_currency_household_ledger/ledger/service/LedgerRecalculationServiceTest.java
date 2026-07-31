package com.self.multi_currency_household_ledger.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import com.self.multi_currency_household_ledger.ledger.service.LedgerRecalculationChunkProcessor.ChunkResult;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

@ExtendWith(MockitoExtension.class)
class LedgerRecalculationServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 4, 6);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-04-05T15:00:00Z"), KST);
    private static final int WINDOW_DAYS = 7;
    private static final LocalDate WINDOW_START = TODAY.minusDays(WINDOW_DAYS);

    @Mock
    private LedgerRecalculationChunkProcessor chunkProcessor;

    @Test
    @DisplayName("첫 청크는 보정창 시작일 커서에서 연다")
    void first_chunk_opens_at_correction_window_start() {
        given(chunkProcessor.recalculateChunk(WINDOW_START, Long.MIN_VALUE, 2)).willReturn(lastChunk(1));

        int recalculated = service(2, 100).recalculateRecentForeignEntries();

        assertThat(recalculated).isEqualTo(1);
        then(chunkProcessor).should().recalculateChunk(WINDOW_START, Long.MIN_VALUE, 2);
    }

    @Test
    @DisplayName("청크가 가득 차면 마지막 커서에서 다음 청크를 이어 처리한다")
    void continues_from_next_cursor_until_last_chunk() {
        given(chunkProcessor.recalculateChunk(WINDOW_START, Long.MIN_VALUE, 2))
                .willReturn(chunk(2, TODAY.minusDays(1), 10L));
        given(chunkProcessor.recalculateChunk(TODAY.minusDays(1), 10L, 2)).willReturn(chunk(2, TODAY, 20L));
        given(chunkProcessor.recalculateChunk(TODAY, 20L, 2)).willReturn(lastChunk(1));

        int recalculated = service(2, 100).recalculateRecentForeignEntries();

        assertThat(recalculated).isEqualTo(5);
    }

    @Test
    @DisplayName("상한에 도달하면 그 주기를 정상 종료하고, 다음 주기가 보정창 처음부터 나머지를 이어받는다")
    void stops_at_max_entries_per_run_and_next_run_picks_up_the_rest() {
        LedgerRecalculationService service = service(2, 3);
        given(chunkProcessor.recalculateChunk(WINDOW_START, Long.MIN_VALUE, 2)).willReturn(chunk(2, TODAY, 10L));
        given(chunkProcessor.recalculateChunk(TODAY, 10L, 1)).willReturn(chunk(1, TODAY, 11L));

        int firstRun = service.recalculateRecentForeignEntries();

        assertThat(firstRun).isEqualTo(3);
        then(chunkProcessor).should(never()).recalculateChunk(TODAY, 11L, 2);

        // 갱신된 행은 대상 술어에서 빠지므로, 다음 주기도 같은 커서(보정창 시작)에서 남은 몫을 잡는다.
        given(chunkProcessor.recalculateChunk(WINDOW_START, Long.MIN_VALUE, 2)).willReturn(lastChunk(2));

        assertThat(service.recalculateRecentForeignEntries()).isEqualTo(2);
    }

    @Test
    @DisplayName("청크 처리가 실패하면 예외를 삼키지 않고 그대로 전파한다")
    void propagates_chunk_failure() {
        given(chunkProcessor.recalculateChunk(WINDOW_START, Long.MIN_VALUE, 2)).willReturn(chunk(2, TODAY, 10L));
        given(chunkProcessor.recalculateChunk(TODAY, 10L, 2)).willThrow(new IllegalStateException("chunk failed"));

        assertThatThrownBy(() -> service(2, 100).recalculateRecentForeignEntries())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("chunk failed");
    }

    @Test
    @DisplayName("낙관적 락 충돌은 같은 커서로 한 번 재시도해 이어서 처리한다")
    void retries_the_same_chunk_once_on_optimistic_lock_conflict() {
        given(chunkProcessor.recalculateChunk(WINDOW_START, Long.MIN_VALUE, 2))
                .willThrow(new ObjectOptimisticLockingFailureException("LedgerEntry", 1L))
                .willReturn(chunk(2, TODAY, 10L));
        given(chunkProcessor.recalculateChunk(TODAY, 10L, 2)).willReturn(lastChunk(1));

        int recalculated = service(2, 100).recalculateRecentForeignEntries();

        assertThat(recalculated).isEqualTo(3);
        then(chunkProcessor).should(times(2)).recalculateChunk(WINDOW_START, Long.MIN_VALUE, 2);
    }

    @Test
    @DisplayName("재시도도 충돌하면 예외 없이 그 주기를 종료하고 앞 청크 처리분을 보고한다")
    void ends_the_run_without_failing_when_the_retry_also_conflicts() {
        given(chunkProcessor.recalculateChunk(WINDOW_START, Long.MIN_VALUE, 2)).willReturn(chunk(2, TODAY, 10L));
        given(chunkProcessor.recalculateChunk(TODAY, 10L, 2))
                .willThrow(new ObjectOptimisticLockingFailureException("LedgerEntry", 1L));

        int recalculated = service(2, 100).recalculateRecentForeignEntries();

        assertThat(recalculated).isEqualTo(2);
        then(chunkProcessor).should(times(2)).recalculateChunk(TODAY, 10L, 2);
    }

    @Test
    @DisplayName("청크 크기·주기 상한이 1 미만이면 배치가 조용히 죽지 않도록 기동에서 막는다")
    void rejects_non_positive_chunk_settings() {
        assertThatThrownBy(() -> service(0, 100)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service(2, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    private LedgerRecalculationService service(int chunkSize, int maxEntriesPerRun) {
        return new LedgerRecalculationService(chunkProcessor, FIXED_CLOCK, WINDOW_DAYS, chunkSize, maxEntriesPerRun);
    }

    private ChunkResult chunk(int recalculated, LocalDate nextCursorDate, Long nextCursorId) {
        return new ChunkResult(recalculated, nextCursorDate, nextCursorId);
    }

    private ChunkResult lastChunk(int recalculated) {
        return new ChunkResult(recalculated, null, null);
    }
}

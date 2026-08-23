package com.self.multi_currency_household_ledger.member.service;

import com.self.multi_currency_household_ledger.member.repository.AuthUserRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 보유기간을 넘긴 <b>버려진 익명 계정</b>을 물리 삭제한다. 앱 삭제·기기 분실·세션 유실로 버려진 익명 계정은
 * 아무도 삭제 버튼을 눌러주지 않아 영구히 남으므로(개인정보 보유기간 위반 + DB 무한 누적) 서버가 회수한다.
 * {@code auth.users} 행을 지우면 FK cascade 가 거래·커스텀 카테고리까지 함께 회수한다.
 *
 * <p>{@code enabled=false} 는 "아무것도 안 함"이 아니라 <b>관측 전용 모드</b>다 — 후보 SELECT 와 카운트는
 * 그대로 돌고 DELETE 만 생략한다. 그래야 첫 삭제가 발생하기 전에 운영 스키마·권한이 매일 검증된다.
 *
 * <p>트랜잭션 경계는 {@link MemberWithdrawalService} 와 같이 루프 안에서 {@link TransactionTemplate} 으로
 * 만든다. 같은 클래스의 {@code @Transactional} 메서드를 자기호출하면 프록시를 지나지 않아 트랜잭션이
 * 아예 사라진다({@code LedgerRecalculationChunkProcessor} javadoc 참조).
 */
@Slf4j
@Service
public class AnonymousAccountCleanupService {

    private static final String COUNTER_NAME = "woni.member.anonymous_cleanup";
    private static final int MIN_RETENTION_DAYS = 30;
    private static final int MAX_RETENTION_DAYS = 3650;
    private static final int MIN_BATCH_LIMIT = 1;
    private static final int MAX_BATCH_LIMIT = 10_000;

    private final AuthUserRepository authUserRepository;
    private final MeterRegistry meterRegistry;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final boolean enabled;
    private final int retentionDays;
    private final int batchLimit;

    public AnonymousAccountCleanupService(
            AuthUserRepository authUserRepository,
            MeterRegistry meterRegistry,
            PlatformTransactionManager transactionManager,
            Clock clock,
            @Value("${member.anonymous-cleanup.enabled:false}") boolean enabled,
            @Value("${member.anonymous-cleanup.retention-days:365}") int retentionDays,
            @Value("${member.anonymous-cleanup.batch-limit:500}") int batchLimit) {
        validateRetentionDays(retentionDays);
        validateBatchLimit(batchLimit);
        this.authUserRepository = authUserRepository;
        this.meterRegistry = meterRegistry;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.enabled = enabled;
        this.retentionDays = retentionDays;
        this.batchLimit = batchLimit;
    }

    public void cleanupAbandonedAnonymousAccounts() {
        Instant cutoffInstant = clock.instant().minus(retentionDays, ChronoUnit.DAYS);
        // auth.* 는 timestamptz 지만 ledger_entry·category 의 감사 컬럼은 JpaAuditing 이 JVM 기본 존으로 기록한
        // naive timestamp 다. 그래서 clock.getZone() 이 아니라 ZoneId.systemDefault() 이 맞는 변환이다.
        LocalDateTime cutoffLocal = LocalDateTime.ofInstant(cutoffInstant, ZoneId.systemDefault());

        List<UUID> candidates = authUserRepository.findAbandonedAnonymousIds(cutoffInstant, cutoffLocal, batchLimit);
        increment("candidate", candidates.size());
        log.info(
                "버려진 익명 계정 정리 후보 조회. candidates={}, retentionDays={}, cutoff={}, enabled={}",
                candidates.size(),
                retentionDays,
                cutoffInstant,
                enabled);
        if (candidates.size() >= batchLimit) {
            log.warn("버려진 익명 계정 정리가 배치 상한에 도달했습니다. 나머지는 다음 주기가 이어받습니다. batchLimit={}", batchLimit);
        }

        if (!enabled) {
            increment("disabled", 1);
            return;
        }

        int deleted = 0;
        int skipped = 0;
        int failed = 0;
        for (UUID memberId : candidates) {
            try {
                int deletedRows = deleteInOwnTransaction(memberId, cutoffInstant, cutoffLocal);
                if (deletedRows > 0) {
                    deleted += deletedRows;
                } else {
                    skipped++;
                }
            } catch (RuntimeException e) {
                // 루프를 끊으면 created_at 정렬 선두의 실패 계정이 매일 재선택돼 그 뒤 계정이 영구히 회수되지 않는다.
                // 식별자는 남기지 않는다 — 파기 대상 UUID 가 로그에 잔존하면 파기 의무에 저촉된다.
                failed++;
                log.error("버려진 익명 계정 삭제에 실패해 다음 계정으로 진행합니다.", e);
            }
        }
        increment("deleted", deleted);
        increment("skipped", skipped);
        increment("error", failed);
        log.info(
                "버려진 익명 계정 정리 완료. deleted={}, skipped={}, failed={}, cutoff={}",
                deleted,
                skipped,
                failed,
                cutoffInstant);
    }

    private int deleteInOwnTransaction(UUID memberId, Instant cutoffInstant, LocalDateTime cutoffLocal) {
        Integer deletedRows = transactionTemplate.execute(
                status -> authUserRepository.deleteAbandonedAnonymousUser(memberId, cutoffInstant, cutoffLocal));
        return deletedRows == null ? 0 : deletedRows;
    }

    private void increment(String result, int amount) {
        Counter.builder(COUNTER_NAME)
                .tag("result", result)
                .register(meterRegistry)
                .increment(amount);
    }

    private static void validateRetentionDays(int retentionDays) {
        if (retentionDays < MIN_RETENTION_DAYS || retentionDays > MAX_RETENTION_DAYS) {
            throw new IllegalArgumentException("member.anonymous-cleanup.retention-days must be between %d and %d"
                    .formatted(MIN_RETENTION_DAYS, MAX_RETENTION_DAYS));
        }
    }

    /**
     * 하한이 0 이 아니라 1 인 것은 {@code limit 0} 이 <b>에러 없이</b> 0행을 주기 때문이다 — 배치는 매일
     * 무음 no-op 이 되는데 동시에 {@code candidates.size() >= batchLimit} 이 {@code 0 >= 0} 으로 참이라
     * "배치 상한 도달" 경고까지 매일 띄운다(로그가 실제 동작과 정반대). 음수는 Postgres 가 예외로 막는다.
     *
     * <p>상한은 오타 한 자리가 한 사이클을 밤새 붙잡는 것을 막는다 — 스케줄러의 중복 실행 가드 때문에
     * 사이클 하나가 길어지면 그 뒤 주기가 통째로 건너뛰어진다. 기본값 500 의 20배라 정상 운영에는 여유가 있다.
     */
    private static void validateBatchLimit(int batchLimit) {
        if (batchLimit < MIN_BATCH_LIMIT || batchLimit > MAX_BATCH_LIMIT) {
            throw new IllegalArgumentException("member.anonymous-cleanup.batch-limit must be between %d and %d"
                    .formatted(MIN_BATCH_LIMIT, MAX_BATCH_LIMIT));
        }
    }
}

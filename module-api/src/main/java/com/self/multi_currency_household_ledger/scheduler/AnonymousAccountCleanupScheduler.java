package com.self.multi_currency_household_ledger.scheduler;

import com.self.multi_currency_household_ledger.member.service.AnonymousAccountCleanupService;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 버려진 익명 계정 정리 배치의 트리거. 정책(플래그·보유기간·상한·메트릭)은 전부 서비스가 갖고 여기서는 호출만 한다. */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnonymousAccountCleanupScheduler {

    private final AnonymousAccountCleanupService cleanupService;
    private final AtomicBoolean running = new AtomicBoolean(false);

    @Scheduled(cron = "0 0 4 * * *", zone = "Asia/Seoul")
    public void cleanupAbandonedAnonymousAccounts() {
        if (!running.compareAndSet(false, true)) {
            log.debug("버려진 익명 계정 정리 사이클이 이미 실행 중이라 건너뜁니다.");
            return;
        }

        try {
            cleanupService.cleanupAbandonedAnonymousAccounts();
        } catch (RuntimeException e) {
            log.error("버려진 익명 계정 정리 스케줄 실패. 다음 주기에 다시 시도합니다.", e);
        } finally {
            running.set(false);
        }
    }
}

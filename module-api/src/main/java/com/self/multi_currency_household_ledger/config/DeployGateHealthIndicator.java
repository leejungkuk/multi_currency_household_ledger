package com.self.multi_currency_household_ledger.config;

import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * 두 색 배포의 투입 게이트. readiness 그룹에 들어가 Caddy 가 이 색으로 트래픽을 보낼지를 정한다 — 배포 에이전트가 스모크를 통과한 색에만 컨테이너 안
 * 게이트 파일을 {@code touch} 하고, 철수할 색에서는 {@code rm} 한다. 레버를 컨테이너 안 파일 하나로 둬서 Caddyfile 은 정적인 사람 소유로 남는다.
 *
 * <p>매 호출마다 파일 존재만 본다(캐시 없음) — 에이전트의 대기 시간은 Caddy 프로브 주기만 계산하면 된다. 경로가 비면 게이트가 없는 것으로 보고 늘 UP
 * 이다(로컬 실행·테스트는 게이트를 모른다). 경로는 이미지가 {@code WONI_DEPLOY_GATE_FILE} 로 정한다.
 */
@Component
class DeployGateHealthIndicator implements HealthIndicator {

    private final String file;

    DeployGateHealthIndicator(@Value("${woni.deploy-gate.file:}") String file) {
        this.file = file;
    }

    @Override
    public Health health() {
        if (file.isEmpty() || Files.exists(Path.of(file))) {
            return Health.up().build();
        }
        return Health.outOfService().build();
    }
}

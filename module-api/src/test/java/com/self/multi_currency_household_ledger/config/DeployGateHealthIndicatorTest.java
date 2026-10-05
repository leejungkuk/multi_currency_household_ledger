package com.self.multi_currency_household_ledger.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

class DeployGateHealthIndicatorTest {

    @TempDir
    private Path tempDir;

    @Test
    @DisplayName("게이트 파일 경로가 설정되지 않으면 UP 이다")
    void gate_is_up_when_not_configured() {
        Health health = new DeployGateHealthIndicator("").health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).isEmpty();
    }

    @Test
    @DisplayName("게이트 파일이 없으면 OUT_OF_SERVICE 다")
    void gate_is_out_of_service_while_file_is_missing() {
        Health health = new DeployGateHealthIndicator(tempDir.resolve("missing").toString()).health();

        assertThat(health.getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
        assertThat(health.getDetails()).isEmpty();
    }

    @Test
    @DisplayName("같은 인스턴스가 매 호출마다 파일 존재를 다시 본다")
    void gate_follows_file_on_every_call() throws Exception {
        Path gate = tempDir.resolve("gate-open");
        DeployGateHealthIndicator indicator = new DeployGateHealthIndicator(gate.toString());

        assertThat(indicator.health().getStatus()).isEqualTo(Status.OUT_OF_SERVICE);

        Files.createFile(gate);
        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);

        Files.delete(gate);
        assertThat(indicator.health().getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
    }
}

package com.self.multi_currency_household_ledger.exchange;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.testcontainers.containers.PostgreSQLContainer;

@SpringBootApplication(
        scanBasePackages = {
            "com.self.multi_currency_household_ledger.exchange",
            "com.self.multi_currency_household_ledger.common"
        })
@EnableJpaAuditing
public class TestExchangeApplication {

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgresContainer() {
        return new PostgreSQLContainer<>("postgres:16-alpine");
    }
}

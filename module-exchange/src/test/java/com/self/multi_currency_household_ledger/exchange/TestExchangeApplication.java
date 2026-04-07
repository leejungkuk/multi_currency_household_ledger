package com.self.multi_currency_household_ledger.exchange;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@SpringBootApplication(scanBasePackages = {
        "com.self.multi_currency_household_ledger.exchange",
        "com.self.multi_currency_household_ledger.common"
})
@EnableJpaAuditing
public class TestExchangeApplication {
}

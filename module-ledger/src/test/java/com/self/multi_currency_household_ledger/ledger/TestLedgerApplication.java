package com.self.multi_currency_household_ledger.ledger;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(
        scanBasePackages = {
            "com.self.multi_currency_household_ledger.ledger",
            "com.self.multi_currency_household_ledger.common"
        })
public class TestLedgerApplication {
    public static void main(String[] args) {
        SpringApplication.run(TestLedgerApplication.class, args);
    }
}

package com.self.multi_currency_household_ledger.ledger;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;

// 엔티티는 운영(ApiApplication)과 같게 루트 패키지에서 스캔한다 — 재계산 대상 쿼리가 ExchangeRate 를 참조하므로
// ledger 패키지만 스캔하면 이 슬라이스에서만 매핑이 빠진다.
@EntityScan("com.self.multi_currency_household_ledger")
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

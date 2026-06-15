package com.self.multi_currency_household_ledger.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TransactionTypeTest {

    // 거래 유형(수입/지출)이 정상적으로 선언되어 있는지 확인한다.
    @Test
    @DisplayName("수입과 지출 타입이 존재한다")
    void transaction_type_exists() {
        assertThat(TransactionType.INCOME).isNotNull();
        assertThat(TransactionType.EXPENSE).isNotNull();
    }
}

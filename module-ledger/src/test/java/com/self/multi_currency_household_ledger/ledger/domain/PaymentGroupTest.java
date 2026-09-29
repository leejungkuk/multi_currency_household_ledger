package com.self.multi_currency_household_ledger.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PaymentGroupTest {

    @ParameterizedTest(name = "자산 {0} → {1}")
    @CsvSource({
        "1, CREDIT_CARD",
        "2, CASH_AND_DEBIT",
        "3, CASH_AND_DEBIT",
        "4, ACCOUNT_AND_OTHER",
        "5, ACCOUNT_AND_OTHER",
        "6, ACCOUNT_AND_OTHER",
        // V6 이 비활성으로 남긴 레거시 자산도 세 그룹 중 하나로 가야 그룹 합계가 전체와 같다.
        "7, ACCOUNT_AND_OTHER",
        "999, ACCOUNT_AND_OTHER"
    })
    void of_maps_asset_id_to_payment_group(long assetId, PaymentGroup expected) {
        assertThat(PaymentGroup.of(assetId)).isEqualTo(expected);
    }
}

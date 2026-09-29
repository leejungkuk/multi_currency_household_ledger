package com.self.multi_currency_household_ledger.ledger.domain;

public enum PaymentGroup {
    CREDIT_CARD,
    CASH_AND_DEBIT,
    ACCOUNT_AND_OTHER;

    /** 자산 id 로 그룹을 정한다. 1·2·3 이 아닌 자산(4·5·6, V6 의 비활성 레거시 자산)은 모두 ACCOUNT_AND_OTHER 다. */
    public static PaymentGroup of(long assetId) {
        if (assetId == 1L) {
            return CREDIT_CARD;
        }
        if (assetId == 2L || assetId == 3L) {
            return CASH_AND_DEBIT;
        }
        return ACCOUNT_AND_OTHER;
    }
}

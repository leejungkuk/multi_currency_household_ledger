package com.self.multi_currency_household_ledger.ledger.dto;

/** 옮긴 달 수·회원에게 이미 있어 건너뛴 달 수. 앱은 화면에 쓰지 않는다(기록용). */
public record GuestBudgetImportResponse(int importedMonthCount, int skippedMonthCount) {}

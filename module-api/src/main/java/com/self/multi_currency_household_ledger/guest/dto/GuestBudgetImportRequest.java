package com.self.multi_currency_household_ledger.guest.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.self.multi_currency_household_ledger.common.exception.BusinessException;
import com.self.multi_currency_household_ledger.ledger.exception.BudgetErrorCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public record GuestBudgetImportRequest(
        @NotBlank String guestAccessToken, @NotNull List<@NotNull @Valid CategoryMapping> categoryMappings) {

    /** 같은 비회원 카테고리가 두 번 오면 어느 쪽으로 옮길지 정할 수 없어 거절한다. */
    @JsonIgnore
    public Map<Long, Long> categoryIdMap() {
        Map<Long, Long> map = new HashMap<>();
        for (CategoryMapping mapping : categoryMappings) {
            if (map.put(mapping.guestCategoryId(), mapping.memberCategoryId()) != null) {
                throw new BusinessException(BudgetErrorCode.BUDGET_INVALID_ALLOCATION);
            }
        }
        return map;
    }

    /** Spring MVC 는 DEBUG 에서 읽은 본문의 toString 을 로그에 찍는다 — 토큰을 가린다. */
    @Override
    public String toString() {
        return "GuestBudgetImportRequest[categoryMappings=" + categoryMappings + "]";
    }

    public record CategoryMapping(@NotNull Long guestCategoryId, @NotNull Long memberCategoryId) {}
}

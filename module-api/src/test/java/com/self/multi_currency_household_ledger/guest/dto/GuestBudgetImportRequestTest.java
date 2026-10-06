package com.self.multi_currency_household_ledger.guest.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.self.multi_currency_household_ledger.common.exception.BusinessException;
import com.self.multi_currency_household_ledger.ledger.exception.BudgetErrorCode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GuestBudgetImportRequestTest {

    @Test
    @DisplayName("toString 은 비회원 토큰을 드러내지 않는다")
    void to_string_does_not_expose_guest_token() {
        GuestBudgetImportRequest request = new GuestBudgetImportRequest(
                "secret-guest-token", List.of(new GuestBudgetImportRequest.CategoryMapping(1L, 2L)));

        assertThat(request.toString()).doesNotContain("secret-guest-token");
    }

    @Test
    @DisplayName("같은 guestCategoryId 가 두 번이면 BUDGET_INVALID_ALLOCATION 이다")
    void duplicate_guest_category_id_is_rejected() {
        GuestBudgetImportRequest request = new GuestBudgetImportRequest(
                "t",
                List.of(
                        new GuestBudgetImportRequest.CategoryMapping(1L, 2L),
                        new GuestBudgetImportRequest.CategoryMapping(1L, 3L)));

        assertThatThrownBy(request::categoryIdMap)
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode())
                        .isEqualTo(BudgetErrorCode.BUDGET_INVALID_ALLOCATION.getCode()));
    }

    @Test
    @DisplayName("서로 다른 guestCategoryId 는 대응표가 된다")
    void distinct_mappings_become_map() {
        GuestBudgetImportRequest request = new GuestBudgetImportRequest(
                "t",
                List.of(
                        new GuestBudgetImportRequest.CategoryMapping(1L, 2L),
                        new GuestBudgetImportRequest.CategoryMapping(3L, 4L)));

        assertThat(request.categoryIdMap()).isEqualTo(Map.of(1L, 2L, 3L, 4L));
    }
}

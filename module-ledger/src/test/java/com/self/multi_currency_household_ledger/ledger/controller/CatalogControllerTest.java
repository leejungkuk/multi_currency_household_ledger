package com.self.multi_currency_household_ledger.ledger.controller;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.self.multi_currency_household_ledger.common.web.CacheControlHeaders;
import com.self.multi_currency_household_ledger.ledger.domain.TransactionType;
import com.self.multi_currency_household_ledger.ledger.dto.AssetResponse;
import com.self.multi_currency_household_ledger.ledger.dto.CategoryResponse;
import com.self.multi_currency_household_ledger.ledger.service.CatalogService;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = CatalogController.class)
class CatalogControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CatalogService catalogService;

    @Test
    @DisplayName("거래 유형별 활성화된 카테고리 목록을 조회한다")
    void get_categories_success() throws Exception {
        given(catalogService.getCategories(TransactionType.EXPENSE))
                .willReturn(List.of(new CategoryResponse(1L, "FOOD_DINING", "식비", "Food & Dining", "🍽️", 1)));

        mockMvc.perform(get("/api/v1/categories")
                        .param("transactionType", "EXPENSE")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, CacheControlHeaders.PUBLIC_READ))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].code").value("FOOD_DINING"))
                .andExpect(jsonPath("$.data[0].displayNameKo").value("식비"))
                .andExpect(jsonPath("$.data[0].displayNameEn").value("Food & Dining"))
                .andExpect(jsonPath("$.data[0].icon").value("🍽️"));
    }

    @Test
    @DisplayName("활성화된 자산 목록을 조회한다")
    void get_assets_success() throws Exception {
        given(catalogService.getAssets()).willReturn(List.of(new AssetResponse(3L, "CASH", "현금", "Cash", 3)));

        mockMvc.perform(get("/api/v1/assets").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, CacheControlHeaders.PUBLIC_READ))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].code").value("CASH"))
                .andExpect(jsonPath("$.data[0].displayNameKo").value("현금"))
                .andExpect(jsonPath("$.data[0].displayNameEn").value("Cash"))
                .andExpect(jsonPath("$.data[0].icon").doesNotExist());
    }
}

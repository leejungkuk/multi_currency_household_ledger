package com.self.multi_currency_household_ledger.ledger.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.self.multi_currency_household_ledger.exchange.domain.CurrencyCode;
import com.self.multi_currency_household_ledger.ledger.domain.TransactionType;
import com.self.multi_currency_household_ledger.ledger.dto.AssetResponse;
import com.self.multi_currency_household_ledger.ledger.dto.CategoryResponse;
import com.self.multi_currency_household_ledger.ledger.dto.CreateLedgerEntryRequest;
import com.self.multi_currency_household_ledger.ledger.dto.LedgerEntryResponse;
import com.self.multi_currency_household_ledger.ledger.service.LedgerService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = LedgerController.class)
class LedgerControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private LedgerService ledgerService;

    @Test
    @DisplayName("가계부 내역을 정상적으로 생성한다")
    void create_ledger_entry_success() throws Exception {
        CreateLedgerEntryRequest request = new CreateLedgerEntryRequest(
                BigDecimal.valueOf(5000), CurrencyCode.KRW, 1L, 1L, LocalDate.now(ZoneId.of("Asia/Seoul")), "커피");

        CategoryResponse categoryResponse = new CategoryResponse(1L, "FOOD", "식비", "icon-food", 1);
        AssetResponse assetResponse = new AssetResponse(1L, "CASH", "현금", "icon-cash", 1);

        LedgerEntryResponse response = new LedgerEntryResponse(
                1L,
                TransactionType.EXPENSE,
                categoryResponse,
                assetResponse,
                BigDecimal.valueOf(5000),
                CurrencyCode.KRW,
                BigDecimal.ONE,
                BigDecimal.valueOf(5000),
                null,
                LocalDate.now(ZoneId.of("Asia/Seoul")),
                "커피");

        given(ledgerService.create(any(CreateLedgerEntryRequest.class), eq(1L))).willReturn(response);

        mockMvc.perform(post("/api/ledgers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(1L))
                .andExpect(jsonPath("$.data.originalAmount").value(5000));
    }

    @Test
    @DisplayName("필수값이 누락되면 400 에러를 반환한다")
    void create_ledger_entry_fails_when_invalid_request() throws Exception {
        CreateLedgerEntryRequest request = new CreateLedgerEntryRequest(null, null, null, null, null, null);

        mockMvc.perform(post("/api/ledgers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }
}

package com.self.multi_currency_household_ledger.ledger.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.self.multi_currency_household_ledger.common.annotation.CurrentMemberId;
import com.self.multi_currency_household_ledger.common.exception.BusinessException;
import com.self.multi_currency_household_ledger.exchange.domain.CurrencyCode;
import com.self.multi_currency_household_ledger.ledger.domain.TransactionType;
import com.self.multi_currency_household_ledger.ledger.dto.AssetResponse;
import com.self.multi_currency_household_ledger.ledger.dto.CategoryResponse;
import com.self.multi_currency_household_ledger.ledger.dto.CreateLedgerEntryRequest;
import com.self.multi_currency_household_ledger.ledger.dto.ImportLedgerEntriesRequest;
import com.self.multi_currency_household_ledger.ledger.dto.ImportLedgerEntriesResponse;
import com.self.multi_currency_household_ledger.ledger.dto.LedgerEntryResponse;
import com.self.multi_currency_household_ledger.ledger.dto.LedgerMonthlySummaryResponse;
import com.self.multi_currency_household_ledger.ledger.dto.LedgerReportResponse;
import com.self.multi_currency_household_ledger.ledger.dto.LedgerRestoreResponse;
import com.self.multi_currency_household_ledger.ledger.dto.SyncLedgerEntryRequest;
import com.self.multi_currency_household_ledger.ledger.dto.SyncLedgerEntryResponse;
import com.self.multi_currency_household_ledger.ledger.exception.LedgerErrorCode;
import com.self.multi_currency_household_ledger.ledger.service.LedgerService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@WebMvcTest(controllers = LedgerController.class)
@Import(LedgerControllerTest.CurrentMemberIdResolverConfig.class)
class LedgerControllerTest {

    private static final UUID MEMBER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

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

        CategoryResponse categoryResponse = new CategoryResponse(1L, "FOOD_DINING", "식비", "Food & Dining", "🍽️", 1);
        AssetResponse assetResponse = new AssetResponse(3L, "CASH", "현금", "Cash", 3);

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

        given(ledgerService.create(any(CreateLedgerEntryRequest.class), eq(MEMBER_ID)))
                .willReturn(response);

        mockMvc.perform(post("/api/v1/ledgers")
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

        mockMvc.perform(post("/api/v1/ledgers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("게스트 거래 배치를 import하고 clientEntryId별 서버 거래 매핑을 반환한다")
    void import_ledger_entries_success() throws Exception {
        UUID clientEntryId = UUID.fromString("10000000-0000-0000-0000-000000000001");
        LocalDate transactionDate = LocalDate.of(2026, 4, 6);
        ImportLedgerEntriesRequest request =
                new ImportLedgerEntriesRequest(List.of(new ImportLedgerEntriesRequest.ImportLedgerEntryItem(
                        clientEntryId, new BigDecimal("100.00"), CurrencyCode.USD, 1L, 3L, transactionDate, "점심")));
        CategoryResponse categoryResponse = new CategoryResponse(1L, "FOOD_DINING", "식비", "Food & Dining", "🍽️", 1);
        AssetResponse assetResponse = new AssetResponse(3L, "CASH", "현금", "Cash", 3);
        LedgerEntryResponse ledgerEntry = new LedgerEntryResponse(
                10L,
                TransactionType.EXPENSE,
                categoryResponse,
                assetResponse,
                new BigDecimal("100.00"),
                CurrencyCode.USD,
                new BigDecimal("1300.000000"),
                new BigDecimal("130000.00"),
                transactionDate,
                transactionDate,
                "점심");
        ImportLedgerEntriesResponse response = new ImportLedgerEntriesResponse(
                List.of(new ImportLedgerEntriesResponse.ImportedLedgerEntry(clientEntryId, ledgerEntry)));

        given(ledgerService.importEntries(any(ImportLedgerEntriesRequest.class), eq(MEMBER_ID)))
                .willReturn(response);

        mockMvc.perform(post("/api/v1/ledgers/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.entries[0].clientEntryId").value(clientEntryId.toString()))
                .andExpect(jsonPath("$.data.entries[0].ledgerEntry.id").value(10L))
                .andExpect(
                        jsonPath("$.data.entries[0].ledgerEntry.currencyCode").value("USD"))
                .andExpect(jsonPath("$.data.entries[0].ledgerEntry.appliedRate").value(1300.000000))
                .andExpect(jsonPath("$.data.entries[0].ledgerEntry.krwAmount").value(130000.00));
    }

    @Test
    @DisplayName("import 충돌은 409 LEDGER_IMPORT_CONFLICT를 반환한다")
    void import_ledger_entries_conflict_returns_409() throws Exception {
        UUID clientEntryId = UUID.fromString("10000000-0000-0000-0000-000000000001");
        ImportLedgerEntriesRequest request =
                new ImportLedgerEntriesRequest(List.of(new ImportLedgerEntriesRequest.ImportLedgerEntryItem(
                        clientEntryId,
                        new BigDecimal("100.00"),
                        CurrencyCode.KRW,
                        1L,
                        3L,
                        LocalDate.of(2026, 4, 6),
                        "커피")));
        given(ledgerService.importEntries(any(ImportLedgerEntriesRequest.class), eq(MEMBER_ID)))
                .willThrow(new BusinessException(LedgerErrorCode.LEDGER_IMPORT_CONFLICT));

        mockMvc.perform(post("/api/v1/ledgers/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("LEDGER_IMPORT_CONFLICT"));
    }

    @Test
    @DisplayName("인증된 import 요청에 null 항목이 있으면 400 VALIDATION_ERROR를 반환한다")
    void import_ledger_entries_fails_when_entries_contain_null_item() throws Exception {
        mockMvc.perform(post("/api/v1/ledgers/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entries\":[null]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        then(ledgerService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("단건 sync upsert는 clientEntryId별 서버 거래 매핑을 반환한다")
    void sync_ledger_entry_success() throws Exception {
        UUID clientEntryId = UUID.fromString("10000000-0000-0000-0000-000000000101");
        LocalDate transactionDate = LocalDate.of(2026, 4, 6);
        SyncLedgerEntryRequest request = new SyncLedgerEntryRequest(
                clientEntryId, new BigDecimal("100.00"), CurrencyCode.USD, 1L, 3L, transactionDate, "점심");
        CategoryResponse categoryResponse = new CategoryResponse(1L, "FOOD_DINING", "식비", "Food & Dining", "🍽️", 1);
        AssetResponse assetResponse = new AssetResponse(3L, "CASH", "현금", "Cash", 3);
        LedgerEntryResponse ledgerEntry = new LedgerEntryResponse(
                11L,
                TransactionType.EXPENSE,
                categoryResponse,
                assetResponse,
                new BigDecimal("100.00"),
                CurrencyCode.USD,
                new BigDecimal("1320.000000"),
                new BigDecimal("132000.00"),
                transactionDate,
                transactionDate,
                "점심");
        SyncLedgerEntryResponse response = new SyncLedgerEntryResponse(clientEntryId, ledgerEntry);

        given(ledgerService.sync(any(SyncLedgerEntryRequest.class), eq(MEMBER_ID)))
                .willReturn(response);

        mockMvc.perform(post("/api/v1/ledgers/sync")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.clientEntryId").value(clientEntryId.toString()))
                .andExpect(jsonPath("$.data.ledgerEntry.id").value(11L))
                .andExpect(jsonPath("$.data.ledgerEntry.currencyCode").value("USD"))
                .andExpect(jsonPath("$.data.ledgerEntry.appliedRate").value(1320.000000))
                .andExpect(jsonPath("$.data.ledgerEntry.krwAmount").value(132000.00));

        then(ledgerService).should().sync(any(SyncLedgerEntryRequest.class), eq(MEMBER_ID));
    }

    @Test
    @DisplayName("sync upsert 필수값이 누락되면 400 VALIDATION_ERROR를 반환한다")
    void sync_ledger_entry_fails_when_invalid_request() throws Exception {
        mockMvc.perform(post("/api/v1/ledgers/sync")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        then(ledgerService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("clientEntryId 기준 sync delete는 성공 응답을 반환한다")
    void delete_synced_ledger_entry_success() throws Exception {
        UUID clientEntryId = UUID.fromString("10000000-0000-0000-0000-000000000201");

        mockMvc.perform(delete("/api/v1/ledgers/sync/{clientEntryId}", clientEntryId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        then(ledgerService).should().deleteSyncedEntry(clientEntryId, MEMBER_ID);
    }

    @Test
    @DisplayName("가계부 내역을 전체 교체로 수정한다")
    void update_ledger_entry_success() throws Exception {
        LocalDate transactionDate = LocalDate.of(2026, 4, 6);
        CreateLedgerEntryRequest request =
                new CreateLedgerEntryRequest(new BigDecimal("50.00"), CurrencyCode.EUR, 2L, 2L, transactionDate, "수정");
        CategoryResponse categoryResponse = new CategoryResponse(14L, "SALARY", "급여", "Salary", "💼", 1);
        AssetResponse assetResponse = new AssetResponse(1L, "CREDIT_CARD", "신용카드", "Credit Card", 1);
        LedgerEntryResponse response = new LedgerEntryResponse(
                1L,
                TransactionType.INCOME,
                categoryResponse,
                assetResponse,
                new BigDecimal("50.00"),
                CurrencyCode.EUR,
                new BigDecimal("1400.000000"),
                new BigDecimal("70000.00"),
                transactionDate,
                transactionDate,
                "수정");

        given(ledgerService.update(eq(1L), any(CreateLedgerEntryRequest.class), eq(MEMBER_ID)))
                .willReturn(response);

        mockMvc.perform(put("/api/v1/ledgers/{id}", 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(1L))
                .andExpect(jsonPath("$.data.transactionType").value("INCOME"))
                .andExpect(jsonPath("$.data.currencyCode").value("EUR"))
                .andExpect(jsonPath("$.data.appliedRate").value(1400.000000))
                .andExpect(jsonPath("$.data.krwAmount").value(70000.00));
    }

    @Test
    @DisplayName("타 회원 또는 없는 가계부 내역 수정은 404를 반환한다")
    void update_ledger_entry_not_found_returns_404() throws Exception {
        CreateLedgerEntryRequest request = new CreateLedgerEntryRequest(
                BigDecimal.valueOf(5000), CurrencyCode.KRW, 1L, 1L, LocalDate.of(2026, 4, 6), "커피");
        given(ledgerService.update(eq(99L), any(CreateLedgerEntryRequest.class), eq(MEMBER_ID)))
                .willThrow(new BusinessException(LedgerErrorCode.LEDGER_ENTRY_NOT_FOUND));

        mockMvc.perform(put("/api/v1/ledgers/{id}", 99L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("LEDGER_ENTRY_NOT_FOUND"));
    }

    @Test
    @DisplayName("가계부 내역을 삭제한다")
    void delete_ledger_entry_success() throws Exception {
        mockMvc.perform(delete("/api/v1/ledgers/{id}", 1L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("타 회원 또는 없는 가계부 내역 삭제는 404를 반환한다")
    void delete_ledger_entry_not_found_returns_404() throws Exception {
        willThrow(new BusinessException(LedgerErrorCode.LEDGER_ENTRY_NOT_FOUND))
                .given(ledgerService)
                .delete(99L, MEMBER_ID);

        mockMvc.perform(delete("/api/v1/ledgers/{id}", 99L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("LEDGER_ENTRY_NOT_FOUND"));
    }

    @Test
    @DisplayName("월 요약을 조회한다")
    void get_monthly_summary_success() throws Exception {
        LedgerMonthlySummaryResponse response =
                new LedgerMonthlySummaryResponse(new BigDecimal("3000.00"), new BigDecimal("1200.00"));

        given(ledgerService.getMonthlySummary(MEMBER_ID, 2026, 4)).willReturn(response);

        mockMvc.perform(get("/api/v1/ledgers/summary").param("year", "2026").param("month", "4"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.income").value(3000.00))
                .andExpect(jsonPath("$.data.expense").value(1200.00))
                .andExpect(jsonPath("$.data.total").value(1800.00));
    }

    @Test
    @DisplayName("월 거래 목록을 조회한다")
    void get_monthly_entries_success() throws Exception {
        CategoryResponse categoryResponse = new CategoryResponse(1L, "FOOD_DINING", "식비", "Food & Dining", "🍽️", 1);
        AssetResponse assetResponse = new AssetResponse(3L, "CASH", "현금", "Cash", 3);
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
                LocalDate.of(2026, 4, 6),
                "커피");

        given(ledgerService.getMonthlyEntries(MEMBER_ID, 2026, 4)).willReturn(List.of(response));

        mockMvc.perform(get("/api/v1/ledgers").param("year", "2026").param("month", "4"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].id").value(1L))
                .andExpect(jsonPath("$.data[0].memo").value("커피"));
    }

    @Test
    @DisplayName("restore는 clientEntryId를 inline으로 포함하고 keyset 커서를 반환한다")
    void restore_ledger_entries_success() throws Exception {
        UUID clientEntryId = UUID.fromString("10000000-0000-0000-0000-000000000301");
        LocalDate transactionDate = LocalDate.of(2026, 4, 5);
        CategoryResponse categoryResponse = new CategoryResponse(1L, "FOOD_DINING", "식비", "Food & Dining", "🍽️", 1);
        AssetResponse assetResponse = new AssetResponse(3L, "CASH", "현금", "Cash", 3);
        LedgerRestoreResponse response = new LedgerRestoreResponse(
                List.of(new LedgerRestoreResponse.RestoredLedgerEntry(
                        11L,
                        clientEntryId,
                        TransactionType.EXPENSE,
                        categoryResponse,
                        assetResponse,
                        new BigDecimal("100.00"),
                        CurrencyCode.USD,
                        new BigDecimal("1320.000000"),
                        new BigDecimal("132000.00"),
                        transactionDate,
                        transactionDate,
                        "점심")),
                new LedgerRestoreResponse.RestoreCursor(transactionDate, 11L),
                true);

        given(ledgerService.restore(MEMBER_ID, LocalDate.of(2026, 4, 6), 12L, 2))
                .willReturn(response);

        mockMvc.perform(get("/api/v1/ledgers/restore")
                        .param("cursorDate", "2026-04-06")
                        .param("cursorId", "12")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.entries[0].id").value(11L))
                .andExpect(jsonPath("$.data.entries[0].clientEntryId").value(clientEntryId.toString()))
                .andExpect(jsonPath("$.data.entries[0].appliedRate").value(1320.000000))
                .andExpect(jsonPath("$.data.entries[0].rateBaseDate").value("2026-04-05"))
                .andExpect(jsonPath("$.data.nextCursor.transactionDate").value("2026-04-05"))
                .andExpect(jsonPath("$.data.nextCursor.id").value(11L))
                .andExpect(jsonPath("$.data.hasNext").value(true));

        then(ledgerService).should().restore(MEMBER_ID, LocalDate.of(2026, 4, 6), 12L, 2);
    }

    @Test
    @DisplayName("월 리포트를 조회한다")
    void get_monthly_report_success() throws Exception {
        CategoryResponse categoryResponse = new CategoryResponse(1L, "FOOD_DINING", "식비", "Food & Dining", "🍽️", 1);
        LedgerReportResponse response = new LedgerReportResponse(
                List.of(
                        new LedgerReportResponse.CurrencySubtotal(
                                CurrencyCode.USD,
                                TransactionType.EXPENSE,
                                new BigDecimal("150.00"),
                                new BigDecimal("195000.00")),
                        new LedgerReportResponse.CurrencySubtotal(
                                CurrencyCode.USD,
                                TransactionType.INCOME,
                                new BigDecimal("200.00"),
                                new BigDecimal("260000.00"))),
                List.of(new LedgerReportResponse.CategorySubtotal(
                        categoryResponse, TransactionType.EXPENSE, new BigDecimal("14000.00"))));

        given(ledgerService.getMonthlyReport(MEMBER_ID, 2026, 4)).willReturn(response);

        mockMvc.perform(get("/api/v1/ledgers/report").param("year", "2026").param("month", "4"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.currencySubtotals[0].currencyCode").value("USD"))
                .andExpect(
                        jsonPath("$.data.currencySubtotals[0].transactionType").value("EXPENSE"))
                .andExpect(
                        jsonPath("$.data.currencySubtotals[0].originalAmount").value(150.00))
                .andExpect(jsonPath("$.data.currencySubtotals[0].krwAmount").value(195000.00))
                .andExpect(
                        jsonPath("$.data.currencySubtotals[1].transactionType").value("INCOME"))
                .andExpect(jsonPath("$.data.categorySubtotals[0].category.code").value("FOOD_DINING"))
                .andExpect(jsonPath("$.data.categorySubtotals[0].category.displayNameKo")
                        .value("식비"))
                .andExpect(jsonPath("$.data.categorySubtotals[0].category.displayNameEn")
                        .value("Food & Dining"))
                .andExpect(
                        jsonPath("$.data.categorySubtotals[0].transactionType").value("EXPENSE"))
                .andExpect(jsonPath("$.data.categorySubtotals[0].krwAmount").value(14000.00));
    }

    @Test
    @DisplayName("월 요약에 범위를 벗어난 month를 주면 400 VALIDATION_ERROR를 반환한다")
    void get_monthly_summary_fails_when_month_out_of_range() throws Exception {
        mockMvc.perform(get("/api/v1/ledgers/summary").param("year", "2026").param("month", "13"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("월 거래 목록에 범위를 벗어난 month를 주면 400 VALIDATION_ERROR를 반환한다")
    void get_monthly_entries_fails_when_month_out_of_range() throws Exception {
        mockMvc.perform(get("/api/v1/ledgers").param("year", "2026").param("month", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("월 리포트에 범위를 벗어난 month를 주면 400 VALIDATION_ERROR를 반환한다")
    void get_monthly_report_fails_when_month_out_of_range() throws Exception {
        mockMvc.perform(get("/api/v1/ledgers/report").param("year", "2026").param("month", "13"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @TestConfiguration
    static class CurrentMemberIdResolverConfig implements WebMvcConfigurer {

        @Override
        public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
            resolvers.add(new HandlerMethodArgumentResolver() {
                @Override
                public boolean supportsParameter(MethodParameter parameter) {
                    return parameter.hasParameterAnnotation(CurrentMemberId.class)
                            && UUID.class.equals(parameter.getParameterType());
                }

                @Override
                public Object resolveArgument(
                        MethodParameter parameter,
                        ModelAndViewContainer mavContainer,
                        NativeWebRequest webRequest,
                        org.springframework.web.bind.support.WebDataBinderFactory binderFactory) {
                    return MEMBER_ID;
                }
            });
        }
    }
}

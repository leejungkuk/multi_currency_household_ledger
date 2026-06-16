package com.self.multi_currency_household_ledger.config;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.self.multi_currency_household_ledger.exchange.domain.CurrencyCode;
import com.self.multi_currency_household_ledger.ledger.controller.LedgerController;
import com.self.multi_currency_household_ledger.ledger.domain.TransactionType;
import com.self.multi_currency_household_ledger.ledger.dto.AssetResponse;
import com.self.multi_currency_household_ledger.ledger.dto.CategoryResponse;
import com.self.multi_currency_household_ledger.ledger.dto.CreateLedgerEntryRequest;
import com.self.multi_currency_household_ledger.ledger.dto.LedgerEntryResponse;
import com.self.multi_currency_household_ledger.ledger.service.LedgerService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.mapping.JpaMetamodelMappingContext;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = LedgerController.class)
@Import({SecurityConfig.class, CurrentMemberIdArgumentResolver.class, WebMvcConfig.class})
@TestPropertySource(
        properties = {
            "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://example.supabase.co/auth/v1",
            "woni.security.jwt.audience=authenticated",
            "woni.security.cors.allowed-origins=http://localhost:3000"
        })
class CurrentMemberIdArgumentResolverTest {

    private static final LocalDate TRANSACTION_DATE = LocalDate.of(2026, 6, 15);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    @SuppressWarnings("UnusedVariable")
    private JwtDecoder jwtDecoder;

    @MockitoBean
    @SuppressWarnings("UnusedVariable")
    private JpaMetamodelMappingContext jpaMetamodelMappingContext;

    @MockitoBean
    private LedgerService ledgerService;

    @Test
    @DisplayName("거래 생성은 토큰 없으면 401 ErrorResponse를 반환한다")
    void create_ledger_without_token_returns_401() throws Exception {
        mockMvc.perform(post("/api/v1/ledgers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("JWT subject UUID가 LedgerService memberId로 전달된다")
    void jwt_subject_is_propagated_to_ledger_service() throws Exception {
        UUID memberId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        given(ledgerService.create(any(CreateLedgerEntryRequest.class), eq(memberId)))
                .willReturn(response());

        mockMvc.perform(post("/api/v1/ledgers")
                        .with(jwt().jwt(token ->
                                token.subject(memberId.toString()).audience(List.of("authenticated"))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        then(ledgerService).should().create(any(CreateLedgerEntryRequest.class), eq(memberId));
    }

    @Test
    @DisplayName("서로 다른 JWT subject는 각각 자기 memberId로 전달된다")
    void different_jwt_subjects_are_propagated_independently() throws Exception {
        UUID firstMemberId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID secondMemberId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        given(ledgerService.create(any(CreateLedgerEntryRequest.class), any(UUID.class)))
                .willReturn(response());

        mockMvc.perform(post("/api/v1/ledgers")
                        .with(jwt().jwt(token ->
                                token.subject(firstMemberId.toString()).audience(List.of("authenticated"))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request())))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/ledgers")
                        .with(jwt().jwt(token ->
                                token.subject(secondMemberId.toString()).audience(List.of("authenticated"))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request())))
                .andExpect(status().isOk());

        then(ledgerService).should().create(any(CreateLedgerEntryRequest.class), eq(firstMemberId));
        then(ledgerService).should().create(any(CreateLedgerEntryRequest.class), eq(secondMemberId));
    }

    @Test
    @DisplayName("JWT subject가 UUID가 아니면 401을 반환한다")
    void invalid_jwt_subject_returns_401() throws Exception {
        mockMvc.perform(post("/api/v1/ledgers")
                        .with(jwt().jwt(token -> token.subject("not-a-uuid").audience(List.of("authenticated"))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    private static CreateLedgerEntryRequest request() {
        return new CreateLedgerEntryRequest(BigDecimal.valueOf(5000), CurrencyCode.KRW, 1L, 1L, TRANSACTION_DATE, "커피");
    }

    private static LedgerEntryResponse response() {
        return new LedgerEntryResponse(
                1L,
                TransactionType.EXPENSE,
                new CategoryResponse(1L, "FOOD_DINING", "식비", "Food & Dining", "🍽️", 1),
                new AssetResponse(3L, "CASH", "현금", "Cash", 3),
                BigDecimal.valueOf(5000),
                CurrencyCode.KRW,
                BigDecimal.ONE,
                BigDecimal.valueOf(5000),
                null,
                TRANSACTION_DATE,
                "커피");
    }
}

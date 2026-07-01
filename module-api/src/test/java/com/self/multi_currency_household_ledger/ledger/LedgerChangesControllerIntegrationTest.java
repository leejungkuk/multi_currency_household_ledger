package com.self.multi_currency_household_ledger.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.self.multi_currency_household_ledger.exchange.domain.CurrencyCode;
import com.self.multi_currency_household_ledger.exchange.service.ExchangeRateService;
import com.self.multi_currency_household_ledger.ledger.domain.LedgerEntryRepository;
import com.self.multi_currency_household_ledger.ledger.dto.SyncLedgerEntryRequest;
import com.self.multi_currency_household_ledger.ledger.dto.SyncLedgerEntryResponse;
import com.self.multi_currency_household_ledger.ledger.service.LedgerService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@TestPropertySource(
        properties = {
            "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://example.supabase.co/auth/v1",
            "exchange.eximbank.api-key=test-api-key"
        })
class LedgerChangesControllerIntegrationTest {

    private static final UUID MEMBER_A = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID MEMBER_B = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final LocalDate TODAY = LocalDate.of(2026, 4, 6);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    @SuppressWarnings("UnusedVariable")
    private JwtDecoder jwtDecoder;

    @MockitoBean
    @SuppressWarnings("UnusedVariable")
    private ExchangeRateService exchangeRateService;

    @BeforeEach
    void setUp() {
        ledgerEntryRepository.deleteAll();
    }

    @Test
    @DisplayName("GET /api/v1/ledgers/changes는 controller 경로에서 다중 페이지를 무중복·무누락 keyset으로 반환한다")
    void changes_endpoint_returns_keyset_pages_without_duplicates_or_omissions() throws Exception {
        SyncLedgerEntryResponse first = sync(MEMBER_A, "10000000-0000-0000-0000-000000000501", "1000.00", "changes 1");
        SyncLedgerEntryResponse second = sync(MEMBER_A, "10000000-0000-0000-0000-000000000502", "2000.00", "changes 2");
        SyncLedgerEntryResponse third = sync(MEMBER_A, "10000000-0000-0000-0000-000000000503", "3000.00", "changes 3");
        setUpdatedAt(first.ledgerEntry().id(), LocalDateTime.of(2026, 4, 6, 9, 0));
        setUpdatedAt(second.ledgerEntry().id(), LocalDateTime.of(2026, 4, 6, 9, 1));
        setUpdatedAt(third.ledgerEntry().id(), LocalDateTime.of(2026, 4, 6, 9, 1));

        JsonNode firstPage = getChanges(MEMBER_A, null, null, 2);
        assertThat(firstPage.path("entries").size()).isEqualTo(2);
        assertThat(firstPage.path("hasMore").asBoolean()).isTrue();
        assertThat(firstPage.path("nextCursor").path("updatedAt").asText()).isEqualTo("2026-04-06T09:01:00");
        assertThat(firstPage.path("nextCursor").path("id").asLong())
                .isEqualTo(second.ledgerEntry().id());

        JsonNode secondPage = getChanges(
                MEMBER_A,
                firstPage.path("nextCursor").path("updatedAt").asText(),
                firstPage.path("nextCursor").path("id").asLong(),
                2);
        assertThat(secondPage.path("entries").size()).isEqualTo(1);
        assertThat(secondPage.path("hasMore").asBoolean()).isFalse();
        assertThat(secondPage.path("nextCursor").path("updatedAt").asText()).isEqualTo("2026-04-06T09:01:00");
        assertThat(secondPage.path("nextCursor").path("id").asLong())
                .isEqualTo(third.ledgerEntry().id());

        List<Long> recoveredIds = new ArrayList<>();
        firstPage
                .path("entries")
                .forEach(entry -> recoveredIds.add(entry.path("id").asLong()));
        secondPage
                .path("entries")
                .forEach(entry -> recoveredIds.add(entry.path("id").asLong()));
        assertThat(recoveredIds)
                .containsExactly(
                        first.ledgerEntry().id(),
                        second.ledgerEntry().id(),
                        third.ledgerEntry().id())
                .doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("GET /api/v1/ledgers/changes는 JWT subject member_id로 격리되어 다른 회원 행을 반환하지 않는다")
    void changes_endpoint_filters_entries_by_current_member_id() throws Exception {
        SyncLedgerEntryResponse memberA = sync(MEMBER_A, "10000000-0000-0000-0000-000000000504", "1000.00", "memberA");
        SyncLedgerEntryResponse memberB = sync(MEMBER_B, "10000000-0000-0000-0000-000000000505", "2000.00", "memberB");
        setUpdatedAt(memberA.ledgerEntry().id(), LocalDateTime.of(2026, 4, 6, 9, 0));
        setUpdatedAt(memberB.ledgerEntry().id(), LocalDateTime.of(2026, 4, 6, 9, 0));

        JsonNode response = getChanges(MEMBER_B, null, null, 500);

        assertThat(response.path("entries").size()).isEqualTo(1);
        assertThat(response.path("entries").get(0).path("id").asLong())
                .isEqualTo(memberB.ledgerEntry().id());
        assertThat(response.path("entries").get(0).path("id").asLong())
                .isNotEqualTo(memberA.ledgerEntry().id());
        assertThat(response.path("entries").get(0).path("clientEntryId").asText())
                .isEqualTo(memberB.clientEntryId().toString());
    }

    @Test
    @DisplayName("GET /api/v1/ledgers/changes는 부분 커서(cursorUpdatedAt/cursorId 한쪽만)면 400 INVALID_CHANGES_CURSOR를 반환한다")
    void changes_endpoint_rejects_partial_cursor_with_400() throws Exception {
        mockMvc.perform(get("/api/v1/ledgers/changes")
                        .with(jwt().jwt(token ->
                                token.subject(MEMBER_A.toString()).audience(List.of("authenticated"))))
                        .param("cursorUpdatedAt", "2026-04-06T09:00:00"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("INVALID_CHANGES_CURSOR"));

        mockMvc.perform(get("/api/v1/ledgers/changes")
                        .with(jwt().jwt(token ->
                                token.subject(MEMBER_A.toString()).audience(List.of("authenticated"))))
                        .param("cursorId", "10"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("INVALID_CHANGES_CURSOR"));
    }

    private SyncLedgerEntryResponse sync(UUID memberId, String clientEntryId, String amount, String memo) {
        return ledgerService.sync(
                new SyncLedgerEntryRequest(
                        UUID.fromString(clientEntryId), new BigDecimal(amount), CurrencyCode.KRW, 1L, 3L, TODAY, memo),
                memberId);
    }

    private JsonNode getChanges(UUID memberId, String cursorUpdatedAt, Long cursorId, int size) throws Exception {
        var request = get("/api/v1/ledgers/changes")
                .with(jwt().jwt(token -> token.subject(memberId.toString()).audience(List.of("authenticated"))))
                .param("size", Integer.toString(size));
        if (cursorUpdatedAt != null) {
            request.param("cursorUpdatedAt", cursorUpdatedAt);
        }
        if (cursorId != null) {
            request.param("cursorId", Long.toString(cursorId));
        }

        String body = mockMvc.perform(request)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode root = objectMapper.readTree(body);
        assertThat(root.path("success").asBoolean()).isTrue();
        return root.path("data");
    }

    private void setUpdatedAt(Long id, LocalDateTime updatedAt) {
        jdbcTemplate.update("update ledger_entry set updated_at = ? where id = ?", updatedAt, id);
    }

    @TestConfiguration
    static class TestConfig {

        @Bean
        @ServiceConnection
        PostgreSQLContainer<?> postgresContainer() {
            return new PostgreSQLContainer<>("postgres:16-alpine");
        }
    }
}

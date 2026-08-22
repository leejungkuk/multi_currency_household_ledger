package com.self.multi_currency_household_ledger.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.self.multi_currency_household_ledger.AuthUserFixture;
import com.self.multi_currency_household_ledger.exchange.domain.CurrencyCode;
import com.self.multi_currency_household_ledger.exchange.service.ExchangeRateService;
import com.self.multi_currency_household_ledger.ledger.domain.Category;
import com.self.multi_currency_household_ledger.ledger.domain.CategoryRepository;
import com.self.multi_currency_household_ledger.ledger.domain.TransactionType;
import com.self.multi_currency_household_ledger.ledger.dto.SyncLedgerEntryRequest;
import com.self.multi_currency_household_ledger.ledger.service.LedgerService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@TestPropertySource(
        properties = {
            "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://example.supabase.co/auth/v1",
            "exchange.eximbank.api-key=test-api-key",
            "ledger.quota.max-entries-per-member=1"
        })
class LedgerPurgeControllerIntegrationTest {

    private static final UUID MEMBER_A = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID MEMBER_B = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID CLIENT_ENTRY_ID = UUID.fromString("10000000-0000-0000-0000-000000000601");
    private static final LocalDate TRANSACTION_DATE = LocalDate.of(2026, 4, 6);
    private static final long SYSTEM_CATEGORY_ID = 1L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CategoryRepository categoryRepository;

    @MockitoBean
    @SuppressWarnings("UnusedVariable")
    private JwtDecoder jwtDecoder;

    @MockitoBean
    @SuppressWarnings("UnusedVariable")
    private ExchangeRateService exchangeRateService;

    @BeforeEach
    void setUp() {
        new AuthUserFixture(jdbcTemplate).reset(MEMBER_A, MEMBER_B);
    }

    @Test
    @DisplayName("DELETE /api/v1/ledgers는 현재 회원 거래만 지우고 계정과 다른 회원 거래를 보존한다")
    void purge_deletes_only_current_member_entries_and_preserves_account() throws Exception {
        sync(MEMBER_A, CLIENT_ENTRY_ID, "회원 A 거래");
        sync(MEMBER_B, UUID.fromString("10000000-0000-0000-0000-000000000602"), "회원 B 거래");
        Map<String, Object> memberBBefore = memberEntrySnapshot(MEMBER_B);

        String responseBody = purge(MEMBER_A)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode response = objectMapper.readTree(responseBody);
        assertThat(response.path("success").asBoolean()).isTrue();
        assertThat(response.has("data")).isTrue();
        assertThat(response.path("data").isNull()).isTrue();
        assertThat(authUserCount(MEMBER_A)).isEqualTo(1L);
        assertThat(ledgerCount(MEMBER_A)).isZero();
        assertThat(ledgerCount(MEMBER_B)).isEqualTo(1L);
        assertThat(memberEntrySnapshot(MEMBER_B)).isEqualTo(memberBBefore);
    }

    @Test
    @DisplayName("purge는 커스텀 카테고리를 참조하는 거래까지 지우고 그 커스텀 카테고리를 물리 삭제한다")
    void purge_hard_deletes_custom_categories_referenced_by_entries() throws Exception {
        long customCategoryId = createCustomCategory(MEMBER_A, "커스텀 지출", true);
        sync(MEMBER_A, CLIENT_ENTRY_ID, "커스텀 카테고리 거래", customCategoryId);
        long systemCategoriesBefore = systemCategoryCount();
        long assetsBefore = assetCount();

        purge(MEMBER_A).andExpect(status().isOk());

        assertThat(ledgerCount(MEMBER_A)).isZero();
        assertThat(customCategoryCount(MEMBER_A)).isZero();
        assertThat(categoryRowCount(customCategoryId)).isZero();
        assertThat(systemCategoryCount()).isEqualTo(systemCategoriesBefore);
        assertThat(assetCount()).isEqualTo(assetsBefore);
    }

    @Test
    @DisplayName("purge는 거래가 없어도 비활성 커스텀 카테고리까지 물리 삭제한다")
    void purge_hard_deletes_inactive_custom_categories() throws Exception {
        long inactiveCategoryId = createCustomCategory(MEMBER_A, "삭제된 커스텀", false);

        purge(MEMBER_A).andExpect(status().isOk());

        assertThat(categoryRowCount(inactiveCategoryId)).isZero();
        assertThat(customCategoryCount(MEMBER_A)).isZero();
    }

    @Test
    @DisplayName("회원 A의 purge는 회원 B의 활성·비활성 커스텀 카테고리를 모두 보존한다")
    void purge_preserves_custom_categories_of_other_member() throws Exception {
        long memberACategoryId = createCustomCategory(MEMBER_A, "회원 A 커스텀", true);
        long memberBActiveId = createCustomCategory(MEMBER_B, "회원 B 활성", true);
        long memberBInactiveId = createCustomCategory(MEMBER_B, "회원 B 비활성", false);

        purge(MEMBER_A).andExpect(status().isOk());

        assertThat(categoryRowCount(memberACategoryId)).isZero();
        assertThat(categoryRowCount(memberBActiveId)).isEqualTo(1L);
        assertThat(categoryRowCount(memberBInactiveId)).isEqualTo(1L);
        assertThat(customCategoryCount(MEMBER_B)).isEqualTo(2L);
    }

    @Test
    @DisplayName("가계부 전체 삭제를 연속 두 번 호출해도 두 요청 모두 200이다")
    void purge_is_idempotent_for_repeated_requests() throws Exception {
        sync(MEMBER_A, CLIENT_ENTRY_ID, "재호출 거래");

        purge(MEMBER_A).andExpect(status().isOk());
        purge(MEMBER_A).andExpect(status().isOk());

        assertThat(ledgerCount(MEMBER_A)).isZero();
        assertThat(authUserCount(MEMBER_A)).isEqualTo(1L);
    }

    @Test
    @DisplayName("토큰 없는 가계부 전체 삭제 요청은 deny-by-default에 의해 401이다")
    void purge_requires_authentication() throws Exception {
        sync(MEMBER_A, CLIENT_ENTRY_ID, "미인증 보호 거래");

        mockMvc.perform(delete("/api/v1/ledgers")).andExpect(status().isUnauthorized());

        assertThat(ledgerCount(MEMBER_A)).isEqualTo(1L);
    }

    @Test
    @DisplayName("purge 후 같은 clientEntryId sync는 quota와 부분 unique 제약이 풀려 정확히 한 행을 만든다")
    void purge_allows_sync_with_same_client_entry_id_again() throws Exception {
        sync(MEMBER_A, CLIENT_ENTRY_ID, "purge 전 거래");
        Long entryIdBeforePurge = jdbcTemplate.queryForObject(
                "select id from ledger_entry where member_id = ? and client_entry_id = ?",
                Long.class,
                MEMBER_A,
                CLIENT_ENTRY_ID);
        purge(MEMBER_A).andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/ledgers/sync")
                        .with(memberJwt(MEMBER_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request(CLIENT_ENTRY_ID, "purge 후 거래"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.clientEntryId").value(CLIENT_ENTRY_ID.toString()));

        assertThat(ledgerCount(MEMBER_A)).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
                        "select id from ledger_entry where member_id = ? and client_entry_id = ?",
                        Long.class,
                        MEMBER_A,
                        CLIENT_ENTRY_ID))
                .isNotEqualTo(entryIdBeforePurge);
        assertThat(jdbcTemplate.queryForObject(
                        "select memo from ledger_entry where member_id = ? and client_entry_id = ?",
                        String.class,
                        MEMBER_A,
                        CLIENT_ENTRY_ID))
                .isEqualTo("purge 후 거래");
    }

    @Test
    @DisplayName("purge 전에 받은 changes 커서로 다시 조회하면 빈 목록과 null 커서를 반환한다")
    void changes_with_cursor_obtained_before_purge_returns_empty_page() throws Exception {
        sync(MEMBER_A, CLIENT_ENTRY_ID, "changes 커서 거래");
        JsonNode cursor = getChanges(MEMBER_A).path("nextCursor");
        assertThat(cursor.isObject()).isTrue();

        purge(MEMBER_A).andExpect(status().isOk());

        String body = mockMvc.perform(get("/api/v1/ledgers/changes")
                        .with(memberJwt(MEMBER_A))
                        .param("cursorUpdatedAt", cursor.path("updatedAt").asString())
                        .param("cursorId", cursor.path("id").asString()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode data = objectMapper.readTree(body).path("data");

        assertThat(data.path("entries").isEmpty()).isTrue();
        assertThat(data.path("nextCursor").isNull()).isTrue();
        assertThat(data.path("hasMore").asBoolean()).isFalse();
    }

    private void sync(UUID memberId, UUID clientEntryId, String memo) {
        sync(memberId, clientEntryId, memo, SYSTEM_CATEGORY_ID);
    }

    private void sync(UUID memberId, UUID clientEntryId, String memo, long categoryId) {
        ledgerService.sync(request(clientEntryId, memo, categoryId), memberId);
    }

    private static SyncLedgerEntryRequest request(UUID clientEntryId, String memo) {
        return request(clientEntryId, memo, SYSTEM_CATEGORY_ID);
    }

    private static SyncLedgerEntryRequest request(UUID clientEntryId, String memo, long categoryId) {
        return new SyncLedgerEntryRequest(
                clientEntryId, new BigDecimal("1000.00"), CurrencyCode.KRW, categoryId, 3L, TRANSACTION_DATE, memo);
    }

    private long createCustomCategory(UUID memberId, String name, boolean active) {
        Category category = Category.custom(memberId, TransactionType.EXPENSE, name, null);
        if (!active) {
            category.deactivate();
        }
        return categoryRepository.saveAndFlush(category).getId();
    }

    private JsonNode getChanges(UUID memberId) throws Exception {
        String body = mockMvc.perform(get("/api/v1/ledgers/changes").with(memberJwt(memberId)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(body).path("data");
    }

    private ResultActions purge(UUID memberId) throws Exception {
        return mockMvc.perform(delete("/api/v1/ledgers").with(memberJwt(memberId)));
    }

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                    .JwtRequestPostProcessor
            memberJwt(UUID memberId) {
        return jwt().jwt(token -> token.subject(memberId.toString()).audience(List.of("authenticated")));
    }

    private long authUserCount(UUID memberId) {
        Long count = jdbcTemplate.queryForObject("select count(*) from auth.users where id = ?", Long.class, memberId);
        return count == null ? 0L : count;
    }

    private long ledgerCount(UUID memberId) {
        Long count = jdbcTemplate.queryForObject(
                "select count(*) from ledger_entry where member_id = ?", Long.class, memberId);
        return count == null ? 0L : count;
    }

    private long customCategoryCount(UUID memberId) {
        return count("select count(*) from category where owner_member_id = ?", memberId);
    }

    private long categoryRowCount(long categoryId) {
        return count("select count(*) from category where id = ?", categoryId);
    }

    private long systemCategoryCount() {
        return count("select count(*) from category where owner_member_id is null");
    }

    private long assetCount() {
        return count("select count(*) from asset");
    }

    private long count(String sql, Object... args) {
        Long count = jdbcTemplate.queryForObject(sql, Long.class, args);
        return count == null ? 0L : count;
    }

    private Map<String, Object> memberEntrySnapshot(UUID memberId) {
        return jdbcTemplate.queryForMap(
                """
                select client_entry_id, original_amount, currency_code, category_id, asset_id,
                       transaction_date, memo
                from ledger_entry
                where member_id = ?
                """,
                memberId);
    }

    @TestConfiguration
    static class TestConfig {

        @Bean
        @ServiceConnection
        PostgreSQLContainer postgresContainer() {
            return new PostgreSQLContainer("postgres:16-alpine").withInitScript("testcontainers/auth-users-stub.sql");
        }
    }
}

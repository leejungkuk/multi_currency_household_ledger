package com.self.multi_currency_household_ledger.guest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.self.multi_currency_household_ledger.AuthUserFixture;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Base64;
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
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 비회원 예산 가져오기 API — 서버 Clock 은 2026-09-15(KST) 로 고정한다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Import(GuestBudgetImportControllerIntegrationTest.FixedClockConfig.class)
@TestPropertySource(
        properties = {
            "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://example.supabase.co/auth/v1",
            "exchange.eximbank.api-key=test-api-key",
            "woni.security.rate-limit.read-limit=1000",
            "woni.security.rate-limit.write-limit=1000"
        })
class GuestBudgetImportControllerIntegrationTest {

    private static final UUID GUEST = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID MEMBER = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-0000000000a3");
    private static final long DEFAULT_CATEGORY_ID = 1L;
    private static final String IMPORT_URL = "/api/v1/budgets/import-from-guest";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void setUp() {
        new AuthUserFixture(jdbcTemplate).reset(GUEST, MEMBER, OTHER);
        jdbcTemplate.update("delete from exchange_rate");
        stubToken("guest-G", GUEST, true);
    }

    @Test
    @DisplayName("익명 비회원 토큰으로 비회원 예산을 회원 계정으로 복사한다")
    void imports_guest_budgets_with_anonymous_guest_token() throws Exception {
        long guestCategory = createCustomCategory(GUEST, "비회원카테고리");
        long memberCategory = createCustomCategory(MEMBER, "회원카테고리");
        saveBudget(GUEST, 8, guestCategory, DEFAULT_CATEGORY_ID);

        importFrom("guest-G", mappings(guestCategory, memberCategory))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.importedMonthCount").value(1))
                .andExpect(jsonPath("$.data.skippedMonthCount").value(0));

        assertThat(lineIds(readBudget(MEMBER, 8))).containsExactly(memberCategory, DEFAULT_CATEGORY_ID);
        assertThat(budgetRowCount(GUEST)).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 요청을 다시 보내면 건너뛰고 회원 상태는 그대로다")
    void repeated_request_returns_skip_and_keeps_member_state() throws Exception {
        long guestCategory = createCustomCategory(GUEST, "비회원카테고리");
        long memberCategory = createCustomCategory(MEMBER, "회원카테고리");
        saveBudget(GUEST, 8, guestCategory, DEFAULT_CATEGORY_ID);
        String body = mappings(guestCategory, memberCategory);

        importFrom("guest-G", body).andExpect(status().isOk());
        JsonNode first = readBudget(MEMBER, 8);
        importFrom("guest-G", body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.importedMonthCount").value(0))
                .andExpect(jsonPath("$.data.skippedMonthCount").value(1));

        assertThat(readBudget(MEMBER, 8)).isEqualTo(first);
    }

    @Test
    @DisplayName("다른 회원의 회원 토큰을 증거로 내면 403 이고 아무것도 복사하지 않는다 (IDOR)")
    void member_token_as_evidence_is_rejected_and_copies_nothing() throws Exception {
        saveBudget(OTHER, 8, DEFAULT_CATEGORY_ID);
        stubToken("member-B", OTHER, false);
        List<String> otherBefore = budgetSnapshot(OTHER);

        importFrom("member-B", "[]")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("BUDGET_GUEST_TOKEN_INVALID"));

        assertThat(budgetRowCount(MEMBER)).isZero();
        assertThat(budgetRowCount(OTHER)).isEqualTo(1);
        assertThat(budgetSnapshot(OTHER)).isNotEmpty().isEqualTo(otherBefore);
    }

    @Test
    @DisplayName("호출 회원 자신의 토큰은 익명 클레임이 있어도 403 이다")
    void callers_own_token_as_evidence_is_rejected() throws Exception {
        saveBudget(MEMBER, 8, DEFAULT_CATEGORY_ID);
        stubToken("self", MEMBER, true);

        importFrom("self", "[]")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("BUDGET_GUEST_TOKEN_INVALID"));

        assertThat(budgetRowCount(MEMBER)).isEqualTo(1);
    }

    @Test
    @DisplayName("디코더가 거절한 토큰은 클레임이 익명이어도 403 이다")
    void evidence_rejected_by_decoder_is_rejected_even_with_anonymous_claims() throws Exception {
        saveBudget(GUEST, 8, DEFAULT_CATEGORY_ID);
        String payload = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(("{\"sub\":\"" + GUEST + "\",\"is_anonymous\":true}").getBytes(StandardCharsets.UTF_8));
        String forged = "eyJhbGciOiJFUzI1NiJ9." + payload + ".c2ln";
        when(jwtDecoder.decode(forged)).thenThrow(new BadJwtException("expired"));

        importFrom(forged, "[]")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("BUDGET_GUEST_TOKEN_INVALID"));

        assertThat(budgetRowCount(MEMBER)).isZero();
    }

    @Test
    @DisplayName("익명 클레임이 없거나 subject 가 UUID 가 아니면 403 이다")
    void evidence_without_anonymous_claim_or_uuid_subject_is_rejected() throws Exception {
        saveBudget(GUEST, 8, DEFAULT_CATEGORY_ID);
        when(jwtDecoder.decode("no-claim"))
                .thenReturn(Jwt.withTokenValue("no-claim")
                        .header("alg", "ES256")
                        .subject(GUEST.toString())
                        .build());
        when(jwtDecoder.decode("bad-sub"))
                .thenReturn(Jwt.withTokenValue("bad-sub")
                        .header("alg", "ES256")
                        .subject("not-a-uuid")
                        .claim("is_anonymous", true)
                        .build());

        for (String token : List.of("no-claim", "bad-sub")) {
            importFrom(token, "[]")
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("BUDGET_GUEST_TOKEN_INVALID"));
        }
        assertThat(budgetRowCount(MEMBER)).isZero();
    }

    @Test
    @DisplayName("디코더의 키 조회 실패도 비회원 토큰 무효 403 이다 — 500 이 아니다")
    void decoder_key_source_failure_is_rejected_as_invalid_guest_token_not_server_error() throws Exception {
        saveBudget(GUEST, 8, DEFAULT_CATEGORY_ID);
        when(jwtDecoder.decode("jwks-down")).thenThrow(new JwtException("jwks fetch failed"));

        importFrom("jwks-down", "[]")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("BUDGET_GUEST_TOKEN_INVALID"));

        assertThat(budgetRowCount(MEMBER)).isZero();
    }

    @Test
    @DisplayName("대응 대상이 다른 회원의 카테고리면 404 이고 아무것도 복사하지 않는다 (IDOR)")
    void mapping_target_owned_by_other_member_is_not_found() throws Exception {
        long guestCategory = createCustomCategory(GUEST, "비회원카테고리");
        long otherCategory = createCustomCategory(OTHER, "타인카테고리");
        saveBudget(GUEST, 8, guestCategory);
        saveBudget(OTHER, 8, otherCategory);

        importFrom("guest-G", mappings(guestCategory, otherCategory))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CATEGORY_NOT_FOUND"));

        assertThat(budgetRowCount(MEMBER)).isZero();
        assertThat(budgetRowCount(OTHER)).isEqualTo(1);
        assertThat(lineIds(readBudget(OTHER, 8))).containsExactly(otherCategory);
    }

    @Test
    @DisplayName("대응표에 같은 guestCategoryId 가 두 번이면 400 BUDGET_INVALID_ALLOCATION 이다")
    void duplicate_guest_category_mapping_is_invalid_allocation() throws Exception {
        long guestCategory = createCustomCategory(GUEST, "비회원카테고리");
        long memberCategory = createCustomCategory(MEMBER, "회원카테고리");
        saveBudget(GUEST, 8, guestCategory);

        String mapping = "{\"guestCategoryId\":%d,\"memberCategoryId\":%d}".formatted(guestCategory, memberCategory);

        importFrom("guest-G", "[" + mapping + "," + mapping + "]")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BUDGET_INVALID_ALLOCATION"));

        assertThat(budgetRowCount(MEMBER)).isZero();
    }

    @Test
    @DisplayName("guestAccessToken 이 없거나 공백이면 400 VALIDATION_ERROR 이고 디코더를 부르지 않는다")
    void blank_guest_token_is_validation_error() throws Exception {
        for (String body :
                List.of("{\"categoryMappings\":[]}", "{\"guestAccessToken\":\"  \",\"categoryMappings\":[]}")) {
            mockMvc.perform(post(IMPORT_URL)
                            .with(memberJwt(MEMBER))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
        verify(jwtDecoder, never()).decode(anyString());
    }

    private void stubToken(String token, UUID subject, boolean anonymous) {
        when(jwtDecoder.decode(token))
                .thenReturn(Jwt.withTokenValue(token)
                        .header("alg", "ES256")
                        .subject(subject.toString())
                        .claim("is_anonymous", anonymous)
                        .build());
    }

    private ResultActions importFrom(String guestToken, String mappingsJson) throws Exception {
        String body = "{\"guestAccessToken\":\"%s\",\"categoryMappings\":%s}".formatted(guestToken, mappingsJson);
        return mockMvc.perform(post(IMPORT_URL)
                .with(memberJwt(MEMBER))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private static String mappings(long guestCategory, long memberCategory) {
        return "[{\"guestCategoryId\":%d,\"memberCategoryId\":%d}]".formatted(guestCategory, memberCategory);
    }

    private void saveBudget(UUID memberId, int month, long... categoryIds) throws Exception {
        String shares = java.util.Arrays.stream(categoryIds)
                .mapToObj(id -> "{\"categoryId\":%d,\"amount\":1000}".formatted(id))
                .collect(java.util.stream.Collectors.joining(","));
        mockMvc.perform(put("/api/v1/budgets")
                        .with(memberJwt(memberId))
                        .param("year", "2026")
                        .param("month", String.valueOf(month))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currency\":\"KRW\",\"totalAmount\":1000000,\"categoryAmounts\":[%s]}"
                                .formatted(shares)))
                .andExpect(status().isOk());
    }

    private JsonNode readBudget(UUID memberId, int month) throws Exception {
        return objectMapper
                .readTree(mockMvc.perform(get("/api/v1/budgets")
                                .with(memberJwt(memberId))
                                .param("year", "2026")
                                .param("month", String.valueOf(month)))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .path("data");
    }

    private static List<Long> lineIds(JsonNode budget) {
        List<Long> ids = new java.util.ArrayList<>();
        for (JsonNode line : budget.path("categories")) {
            ids.add(line.path("category").path("id").asLong());
        }
        return ids;
    }

    private long createCustomCategory(UUID memberId, String name) throws Exception {
        return objectMapper
                .readTree(mockMvc.perform(post("/api/v1/categories/custom")
                                .with(memberJwt(memberId))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(
                                        Map.of("transactionType", "EXPENSE", "name", name))))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .path("data")
                .path("id")
                .asLong();
    }

    /** 회원의 예산 행·카테고리 몫(카테고리 id·금액·sort_order)을 한 줄씩 읽는다. */
    private List<String> budgetSnapshot(UUID memberId) {
        return jdbcTemplate.queryForList(
                """
                select b.id || '|' || b.total_amount || '|' || coalesce(a.category_id || ':' || a.amount || ':' || a.sort_order, '-')
                from budget b left join budget_category_allocation a on a.budget_id = b.id
                where b.member_id = ?
                order by b.id, a.sort_order, a.category_id
                """,
                String.class,
                memberId);
    }

    private long budgetRowCount(UUID memberId) {
        return jdbcTemplate.queryForObject("select count(*) from budget where member_id = ?", Long.class, memberId);
    }

    private static JwtRequestPostProcessor memberJwt(UUID memberId) {
        return jwt().jwt(token -> token.subject(memberId.toString()).audience(List.of("authenticated")));
    }

    @TestConfiguration
    static class FixedClockConfig {

        @Bean
        @Primary
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-09-15T03:00:00Z"), ZoneId.of("Asia/Seoul"));
        }
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

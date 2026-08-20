package com.self.multi_currency_household_ledger.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.self.multi_currency_household_ledger.AuthUserFixture;
import com.self.multi_currency_household_ledger.exchange.service.ExchangeRateService;
import com.self.multi_currency_household_ledger.ledger.domain.Category;
import com.self.multi_currency_household_ledger.ledger.domain.CategoryRepository;
import com.self.multi_currency_household_ledger.ledger.domain.TransactionType;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@TestPropertySource(
        properties = {
            "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://example.supabase.co/auth/v1",
            "exchange.eximbank.api-key=test-api-key"
        })
class CustomCategoryControllerIntegrationTest {

    private static final UUID MEMBER_A = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID MEMBER_B = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

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
    @DisplayName("커스텀 카테고리 조회·생성·수정·삭제는 인증 없이는 모두 401이다")
    void custom_category_endpoints_require_authentication() throws Exception {
        mockMvc.perform(get("/api/v1/categories/custom").param("transactionType", "EXPENSE"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/categories/custom")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(categoryRequest("반려견")))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(put("/api/v1/categories/custom/{id}", 10_000L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateRequest("반려견")))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/v1/categories/custom/{id}", 10_000L)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("같은 이름을 연속 생성하면 모두 성공하고 내 목록에 생성순으로 나타난다")
    void duplicate_names_are_allowed_and_listed_in_creation_order() throws Exception {
        long firstId = createCategory(MEMBER_A, "반려견");
        long secondId = createCategory(MEMBER_A, "반려견");

        JsonNode data = customCategories(MEMBER_A);

        assertThat(data).hasSize(2);
        assertThat(data.get(0).path("id").asLong()).isEqualTo(firstId);
        assertThat(data.get(1).path("id").asLong()).isEqualTo(secondId);
        assertThat(data.get(0).path("displayNameKo").asString()).isEqualTo("반려견");
        assertThat(data.get(1).path("displayNameKo").asString()).isEqualTo("반려견");
    }

    @Test
    @DisplayName("회원 목록과 삭제는 owner 술어로 격리하고 시스템 카테고리 삭제도 숨긴다")
    void owner_predicate_prevents_idor_and_system_category_deletion() throws Exception {
        createCategory(MEMBER_A, "회원 A");
        long memberBCategoryId = createCategory(MEMBER_B, "회원 B");

        JsonNode memberAData = customCategories(MEMBER_A);
        assertThat(memberAData).hasSize(1);
        assertThat(memberAData.get(0).path("displayNameKo").asString()).isEqualTo("회원 A");

        deleteCategory(MEMBER_A, memberBCategoryId)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CATEGORY_NOT_FOUND"));
        deleteCategory(MEMBER_A, 1L)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CATEGORY_NOT_FOUND"));
    }

    @Test
    @DisplayName("같은 커스텀 카테고리를 연속 삭제해도 둘 다 200이고 목록에서는 사라진다")
    void delete_is_idempotent() throws Exception {
        long categoryId = createCategory(MEMBER_A, "삭제 대상");

        deleteCategory(MEMBER_A, categoryId).andExpect(status().isOk());
        deleteCategory(MEMBER_A, categoryId).andExpect(status().isOk());

        assertThat(customCategories(MEMBER_A)).isEmpty();
        assertThat(isActive(categoryId)).isFalse();
    }

    @Test
    @DisplayName("활성 커스텀 카테고리 100개 상태의 생성 요청은 403이다")
    void create_rejects_active_category_limit() throws Exception {
        categoryRepository.saveAll(IntStream.range(0, 100)
                .mapToObj(index -> Category.custom(MEMBER_A, TransactionType.EXPENSE, "카테고리 " + index, null))
                .toList());

        createCategoryRequest(MEMBER_A, "상한 초과")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CUSTOM_CATEGORY_LIMIT_EXCEEDED"));
    }

    @Test
    @DisplayName("삭제된 auth.users의 잔여 JWT로 생성하면 FK 위반을 401로 매핑한다")
    void remaining_jwt_after_user_deletion_returns_unauthorized() throws Exception {
        jdbcTemplate.update("delete from auth.users where id = ?", MEMBER_A);

        createCategoryRequest(MEMBER_A, "잔여 토큰")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("커스텀 카테고리 삭제 후에도 기존 거래 목록과 월 리포트 소계를 보존한다")
    void soft_delete_preserves_existing_entry_and_report_subtotal() throws Exception {
        long categoryId = createCategory(MEMBER_A, "반려견");
        long ledgerId = createLedger(MEMBER_A, categoryId);

        deleteCategory(MEMBER_A, categoryId).andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/ledgers")
                        .with(memberJwt(MEMBER_A))
                        .param("year", "2026")
                        .param("month", "4"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(ledgerId))
                .andExpect(jsonPath("$.data[0].category.id").value(categoryId))
                .andExpect(jsonPath("$.data[0].category.displayNameKo").value("반려견"));
        mockMvc.perform(get("/api/v1/ledgers/report")
                        .with(memberJwt(MEMBER_A))
                        .param("year", "2026")
                        .param("month", "4"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.categorySubtotals[0].category.id").value(categoryId))
                .andExpect(jsonPath("$.data.categorySubtotals[0].category.displayNameKo")
                        .value("반려견"))
                .andExpect(jsonPath("$.data.categorySubtotals[0].krwAmount").value(1000.00));
    }

    @Test
    @DisplayName("가계부 전체 삭제 후에도 커스텀 카테고리 목록은 유지된다")
    void ledger_purge_preserves_custom_categories() throws Exception {
        long categoryId = createCategory(MEMBER_A, "보존 대상");
        createLedger(MEMBER_A, categoryId);

        mockMvc.perform(delete("/api/v1/ledgers").with(memberJwt(MEMBER_A))).andExpect(status().isOk());

        JsonNode data = customCategories(MEMBER_A);
        assertThat(data).hasSize(1);
        assertThat(data.get(0).path("id").asLong()).isEqualTo(categoryId);
    }

    @Test
    @DisplayName("공개 카테고리 API에는 회원 커스텀 카테고리가 노출되지 않는다")
    void public_categories_exclude_custom_categories_without_token() throws Exception {
        createCategory(MEMBER_A, "비공개 이름");

        String body = mockMvc.perform(get("/api/v1/categories").param("transactionType", "EXPENSE"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode data = objectMapper.readTree(body).path("data");

        assertThat(data).allSatisfy(category -> {
            assertThat(category.path("code").asString()).isNotEqualTo("CUSTOM");
            assertThat(category.path("displayNameKo").asString()).isNotEqualTo("비공개 이름");
        });
    }

    @Test
    @DisplayName("커스텀 카테고리를 수정하면 기존 거래·월 리포트 표시가 새 이름으로 소급되고 재전송도 200이다")
    void rename_is_reflected_in_existing_entries_and_is_idempotent() throws Exception {
        long categoryId = createCategory(MEMBER_A, "햄스장");
        long ledgerId = createLedger(MEMBER_A, categoryId);

        updateCategory(MEMBER_A, categoryId, updateRequest("헬스장"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(categoryId))
                .andExpect(jsonPath("$.data.displayNameKo").value("헬스장"))
                .andExpect(jsonPath("$.data.displayNameEn").value("헬스장"))
                .andExpect(jsonPath("$.data.icon").value("🏋️"));

        mockMvc.perform(get("/api/v1/ledgers")
                        .with(memberJwt(MEMBER_A))
                        .param("year", "2026")
                        .param("month", "4"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(ledgerId))
                .andExpect(jsonPath("$.data[0].category.id").value(categoryId))
                .andExpect(jsonPath("$.data[0].category.displayNameKo").value("헬스장"));
        mockMvc.perform(get("/api/v1/ledgers/report")
                        .with(memberJwt(MEMBER_A))
                        .param("year", "2026")
                        .param("month", "4"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.categorySubtotals[0].category.displayNameKo")
                        .value("헬스장"));

        updateCategory(MEMBER_A, categoryId, updateRequest("헬스장"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.displayNameKo").value("헬스장"));

        JsonNode data = customCategories(MEMBER_A);
        assertThat(data).hasSize(1);
        assertThat(data.get(0).path("displayNameKo").asString()).isEqualTo("헬스장");
    }

    @Test
    @DisplayName("삭제된·타 회원·시스템·미존재 카테고리 수정은 모두 404로 존재를 숨긴다")
    void update_hides_deleted_other_member_and_system_categories() throws Exception {
        long deletedId = createCategory(MEMBER_A, "삭제 대상");
        deleteCategory(MEMBER_A, deletedId).andExpect(status().isOk());
        long memberBCategoryId = createCategory(MEMBER_B, "회원 B");

        assertUpdateNotFound(deletedId);
        assertUpdateNotFound(memberBCategoryId);
        assertUpdateNotFound(1L);
        assertUpdateNotFound(999_999L);

        assertThat(customCategories(MEMBER_B).get(0).path("displayNameKo").asString())
                .isEqualTo("회원 B");
    }

    @Test
    @DisplayName("삭제된 auth.users의 잔여 JWT로 수정하면 커스텀 행이 이미 물리 삭제돼 404다")
    void remaining_jwt_after_user_deletion_returns_not_found_on_update() throws Exception {
        long categoryId = createCategory(MEMBER_A, "잔여 토큰");

        jdbcTemplate.update("delete from auth.users where id = ?", MEMBER_A);

        assertUpdateNotFound(categoryId);
    }

    @Test
    @DisplayName("수정 요청 본문의 여분 필드는 무시하고 거래 유형은 바뀌지 않는다")
    void update_ignores_unknown_fields_and_keeps_transaction_type() throws Exception {
        long categoryId = createCategory(MEMBER_A, "햄스장");

        String bodyWithUnknownFields =
                """
                {
                  "transactionType": "INCOME",
                  "name": "헬스장",
                  "icon": "🏋️",
                  "sortOrder": 1
                }
                """;
        updateCategory(MEMBER_A, categoryId, bodyWithUnknownFields)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.displayNameKo").value("헬스장"))
                .andExpect(jsonPath("$.data.sortOrder").value(1000));

        assertThat(customCategories(MEMBER_A, TransactionType.EXPENSE)).hasSize(1);
        assertThat(customCategories(MEMBER_A, TransactionType.INCOME)).isEmpty();
    }

    @Test
    @DisplayName("수정 트랜잭션이 로드한 뒤 다른 트랜잭션이 삭제를 커밋해도 삭제가 되살아나지 않는다")
    void concurrent_delete_survives_rename_commit() throws Exception {
        long categoryId = createCategory(MEMBER_A, "햄스장");
        TransactionTemplate renameTx = new TransactionTemplate(transactionManager);
        TransactionTemplate deleteTx = new TransactionTemplate(transactionManager);
        deleteTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        renameTx.executeWithoutResult(renameStatus -> {
            Category loaded = categoryRepository.findById(categoryId).orElseThrow();
            assertThat(loaded.isActive()).isTrue();
            deleteTx.executeWithoutResult(deleteStatus ->
                    categoryRepository.findById(categoryId).orElseThrow().deactivate());
            loaded.rename("헬스장", "🏋️");
        });

        assertThat(isActive(categoryId)).isFalse();
        assertThat(jdbcTemplate.queryForObject(
                        "select display_name_ko from category where id = ?", String.class, categoryId))
                .isEqualTo("헬스장");
        assertThat(customCategories(MEMBER_A)).isEmpty();
    }

    private void assertUpdateNotFound(long categoryId) throws Exception {
        updateCategory(MEMBER_A, categoryId, updateRequest("헬스장"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CATEGORY_NOT_FOUND"));
    }

    private ResultActions updateCategory(UUID memberId, long categoryId, String body) throws Exception {
        return mockMvc.perform(put("/api/v1/categories/custom/{id}", categoryId)
                .with(memberJwt(memberId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private Boolean isActive(long categoryId) {
        return jdbcTemplate.queryForObject("select is_active from category where id = ?", Boolean.class, categoryId);
    }

    private long createCategory(UUID memberId, String name) throws Exception {
        MvcResult result = createCategoryRequest(memberId, name)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn();
        return objectMapper
                .readTree(result.getResponse().getContentAsString())
                .path("data")
                .path("id")
                .asLong();
    }

    private ResultActions createCategoryRequest(UUID memberId, String name) throws Exception {
        return mockMvc.perform(post("/api/v1/categories/custom")
                .with(memberJwt(memberId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(categoryRequest(name)));
    }

    private JsonNode customCategories(UUID memberId) throws Exception {
        return customCategories(memberId, TransactionType.EXPENSE);
    }

    private JsonNode customCategories(UUID memberId, TransactionType transactionType) throws Exception {
        String body = mockMvc.perform(get("/api/v1/categories/custom")
                        .with(memberJwt(memberId))
                        .param("transactionType", transactionType.name()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(body).path("data");
    }

    private ResultActions deleteCategory(UUID memberId, long categoryId) throws Exception {
        return mockMvc.perform(
                delete("/api/v1/categories/custom/{id}", categoryId).with(memberJwt(memberId)));
    }

    private long createLedger(UUID memberId, long categoryId) throws Exception {
        String request =
                """
                {
                  "amount": 1000.00,
                  "currencyCode": "KRW",
                  "categoryId": %d,
                  "assetId": 3,
                  "transactionDate": "2026-04-06",
                  "memo": "커스텀 보존 테스트"
                }
                """
                        .formatted(categoryId);
        MvcResult result = mockMvc.perform(post("/api/v1/ledgers")
                        .with(memberJwt(memberId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper
                .readTree(result.getResponse().getContentAsString())
                .path("data")
                .path("id")
                .asLong();
    }

    private static String updateRequest(String name) {
        return """
                {
                  "name": "%s",
                  "icon": "🏋️"
                }
                """
                .formatted(name);
    }

    private static String categoryRequest(String name) {
        return """
                {
                  "transactionType": "EXPENSE",
                  "name": "%s",
                  "icon": "🐶"
                }
                """
                .formatted(name);
    }

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                    .JwtRequestPostProcessor
            memberJwt(UUID memberId) {
        return jwt().jwt(token -> token.subject(memberId.toString()).audience(List.of("authenticated")));
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

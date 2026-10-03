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
import com.self.multi_currency_household_ledger.exchange.domain.CurrencyCode;
import com.self.multi_currency_household_ledger.ledger.domain.PaymentGroup;
import com.self.multi_currency_household_ledger.ledger.dto.SaveBudgetRequest;
import com.self.multi_currency_household_ledger.ledger.service.BudgetService;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import javax.sql.DataSource;
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
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 월 예산 API — 서버 Clock 은 2026-09-15(KST) 로 고정한다. 이번 달 = 2026-09, 쓸 수 있는 달 = 2000-01 ~ 2027-09. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Import(BudgetControllerIntegrationTest.FixedClockConfig.class)
@TestPropertySource(
        properties = {
            "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://example.supabase.co/auth/v1",
            "exchange.eximbank.api-key=test-api-key",
            // 고정 시계라 레이트리밋 윈도가 롤오버되지 않는다 — 클래스 전체 요청이 한 버킷에 쌓여 429 가 섞이지 않게 한도만 올린다.
            "woni.security.rate-limit.read-limit=1000",
            "woni.security.rate-limit.write-limit=1000"
        })
class BudgetControllerIntegrationTest {

    private static final UUID MEMBER_A = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID MEMBER_B = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final YearMonth AUGUST = YearMonth.of(2026, 8);
    private static final YearMonth SEPTEMBER = YearMonth.of(2026, 9);
    private static final YearMonth OCTOBER = YearMonth.of(2026, 10);

    // 시드 카탈로그: 1 = 지출 카테고리, 14 = 수입 카테고리
    private static final long EXPENSE_CATEGORY_ID = 1L;
    private static final long INCOME_CATEGORY_ID = 14L;

    private static final String KRW_1M = """
            {"currency":"KRW","totalAmount":1000000}""";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private BudgetService budgetService;

    @Autowired
    private DataSource dataSource;

    @MockitoBean
    @SuppressWarnings("UnusedVariable")
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void setUp() {
        new AuthUserFixture(jdbcTemplate).reset(MEMBER_A, MEMBER_B);
        jdbcTemplate.update("delete from exchange_rate");
    }

    @Test
    @DisplayName("예산 읽기·저장·삭제는 인증 없이는 모두 401 이다")
    void budget_endpoints_require_authentication() throws Exception {
        mockMvc.perform(get("/api/v1/budgets").param("year", "2026").param("month", "9"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(put("/api/v1/budgets")
                        .param("year", "2026")
                        .param("month", "9")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(KRW_1M))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/v1/budgets").param("year", "2026").param("month", "9"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("A 가 저장한 달은 B 의 같은 달 GET 에 보이지 않는다 (IDOR)")
    void other_member_budget_is_not_visible() throws Exception {
        save(MEMBER_A, SEPTEMBER, KRW_1M).andExpect(status().isOk());

        JsonNode memberB = read(MEMBER_B, SEPTEMBER);
        assertThat(memberB.path("status").asString()).isEqualTo("NOT_SET");
        assertThat(memberB.path("total").isNull()).isTrue();
        assertThat(memberB.path("hasAnyBudget").asBoolean()).isFalse();

        JsonNode memberA = read(MEMBER_A, SEPTEMBER);
        assertThat(memberA.path("status").asString()).isEqualTo("NONE");
        assertThat(memberA.path("total").path("budgetAmount").decimalValue()).isEqualByComparingTo("1000000");
        assertThat(memberA.path("hasAnyBudget").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("B 가 A 의 커스텀 카테고리 id 로 PUT 하면 404 CATEGORY_NOT_FOUND 이고 A 의 행은 그대로다 (IDOR)")
    void other_member_custom_category_is_not_found() throws Exception {
        long memberACategoryId = createCustomCategory(MEMBER_A, "반려견");
        String body = categoryBudget(memberACategoryId, 300000);
        save(MEMBER_A, SEPTEMBER, body).andExpect(status().isOk());

        save(MEMBER_B, SEPTEMBER, body)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CATEGORY_NOT_FOUND"));

        assertThat(budgetRowCount(MEMBER_B)).isZero();
        JsonNode memberA = read(MEMBER_A, SEPTEMBER);
        assertThat(memberA.path("categories")).hasSize(1);
        assertThat(memberA.path("categories").get(0).path("category").path("id").asLong())
                .isEqualTo(memberACategoryId);
        assertThat(memberA.path("categories").get(0).path("budgetAmount").decimalValue())
                .isEqualByComparingTo("300000");
    }

    @Test
    @DisplayName("B 의 같은 달 PUT·DELETE 는 A 의 행·몫을 건드리지 않는다 (IDOR)")
    void other_member_put_and_delete_do_not_touch_member_a_row() throws Exception {
        String memberABody =
                """
                {"currency":"KRW","totalAmount":1000000,
                 "paymentGroupAmounts":[{"paymentGroup":"CREDIT_CARD","amount":600000}],
                 "categoryAmounts":[{"categoryId":%d,"amount":300000}]}"""
                        .formatted(EXPENSE_CATEGORY_ID);
        JsonNode before = data(save(MEMBER_A, SEPTEMBER, memberABody).andExpect(status().isOk()));

        save(MEMBER_B, SEPTEMBER, "{\"currency\":\"USD\",\"totalAmount\":5}").andExpect(status().isOk());
        remove(MEMBER_B, SEPTEMBER)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("NOT_SET"));

        assertThat(budgetRowCount(MEMBER_B)).isZero();
        assertThat(budgetRowCount(MEMBER_A)).isEqualTo(1);
        assertThat(allocationCount(MEMBER_A)).isEqualTo(2);
        assertThat(read(MEMBER_A, SEPTEMBER)).isEqualTo(before);
    }

    @Test
    @DisplayName("A 의 거래는 같은 달·같은 몫으로 저장한 B 의 예산 실제 금액에 들지 않는다 (IDOR — 예산 거래 쿼리의 회원 술어)")
    void other_member_transactions_do_not_count_in_budget() throws Exception {
        String body = categoryBudget(EXPENSE_CATEGORY_ID, 300000);
        save(MEMBER_A, SEPTEMBER, body).andExpect(status().isOk());
        save(MEMBER_B, SEPTEMBER, body).andExpect(status().isOk());
        createEntry(MEMBER_A, "100000", "KRW", EXPENSE_CATEGORY_ID, 1, "2026-09-10"); // 몫 있는 카테고리 · 신용카드
        createEntry(MEMBER_A, "50000", "KRW", 2, 2, "2026-09-11"); // 몫 없는 카테고리 · 현금·체크카드

        JsonNode memberB = read(MEMBER_B, SEPTEMBER);
        assertThat(memberB.path("status").asString()).isEqualTo("NONE");
        assertThat(actualAmounts(memberB))
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO);

        // 대조군 — 같은 거래가 A 의 예산에는 잡힌다.
        JsonNode memberA = read(MEMBER_A, SEPTEMBER);
        assertThat(memberA.path("status").asString()).isEqualTo("IN_PROGRESS");
        assertThat(actualAmounts(memberA))
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(
                        new BigDecimal("150000"),
                        new BigDecimal("100000"),
                        new BigDecimal("50000"),
                        BigDecimal.ZERO,
                        new BigDecimal("100000"),
                        new BigDecimal("50000"));
    }

    @Test
    @DisplayName("달마다 따로다 — M 저장 뒤 M+1 은 미설정이고, M+1 저장이 M 을 바꾸지 않는다")
    void months_are_independent() throws Exception {
        save(MEMBER_A, SEPTEMBER, KRW_1M).andExpect(status().isOk());

        JsonNode october = read(MEMBER_A, OCTOBER);
        assertThat(october.path("status").asString()).isEqualTo("NOT_SET");
        assertThat(october.path("hasAnyBudget").asBoolean()).isTrue();

        save(MEMBER_A, OCTOBER, """
                        {"currency":"USD","totalAmount":700}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.currency").value("USD"));

        JsonNode september = read(MEMBER_A, SEPTEMBER);
        assertThat(september.path("currency").asString()).isEqualTo("KRW");
        assertThat(september.path("total").path("budgetAmount").decimalValue()).isEqualByComparingTo("1000000");
        assertThat(budgetRowCount(MEMBER_A)).isEqualTo(2);
    }

    @Test
    @DisplayName("PUT 은 그 달 세트를 통째로 바꾼다 — 같은 본문 두 번은 둘 다 200·같은 결과, 몫을 뺀 본문이면 그 몫이 사라진다")
    void put_replaces_whole_set_and_is_idempotent() throws Exception {
        String body =
                """
                {"currency":"KRW","totalAmount":1000000,
                 "paymentGroupAmounts":[{"paymentGroup":"CREDIT_CARD","amount":600000}],
                 "categoryAmounts":[{"categoryId":%d,"amount":300000}]}"""
                        .formatted(EXPENSE_CATEGORY_ID);

        JsonNode first = data(save(MEMBER_A, SEPTEMBER, body).andExpect(status().isOk()));
        JsonNode second = data(save(MEMBER_A, SEPTEMBER, body).andExpect(status().isOk()));

        assertThat(second).isEqualTo(first);
        assertThat(allocationCount(MEMBER_A)).isEqualTo(2);
        assertThat(second.path("paymentGroups")).hasSize(3);
        assertThat(second.path("paymentGroups").get(0).path("paymentGroup").asString())
                .isEqualTo("CREDIT_CARD");
        assertThat(second.path("paymentGroups").get(0).path("budgetAmount").decimalValue())
                .isEqualByComparingTo("600000");
        assertThat(second.path("paymentGroups").get(1).path("budgetAmount").isNull())
                .isTrue();
        assertThat(second.path("categories").get(0).path("deleted").asBoolean()).isFalse();
        assertThat(second.path("otherCategories").path("budgetAmount").decimalValue())
                .isEqualByComparingTo("700000");

        JsonNode withoutCategory = data(save(
                        MEMBER_A,
                        SEPTEMBER,
                        """
                                {"currency":"KRW","totalAmount":1000000,
                                 "paymentGroupAmounts":[{"paymentGroup":"CREDIT_CARD","amount":600000}]}""")
                .andExpect(status().isOk()));

        assertThat(withoutCategory.path("categories")).isEmpty();
        assertThat(withoutCategory
                        .path("paymentGroups")
                        .get(0)
                        .path("budgetAmount")
                        .decimalValue())
                .isEqualByComparingTo("600000");
        assertThat(allocationCount(MEMBER_A)).isEqualTo(1);
    }

    @Test
    @DisplayName("DELETE 는 그 달 전체를 NOT_SET 으로 돌려주고, 없는 달을 다시 지워도 200 이다")
    void delete_returns_not_set_month_and_is_idempotent() throws Exception {
        save(MEMBER_A, SEPTEMBER, categoryBudget(EXPENSE_CATEGORY_ID, 300000)).andExpect(status().isOk());

        JsonNode deleted = data(remove(MEMBER_A, SEPTEMBER).andExpect(status().isOk()));

        assertThat(deleted.path("year").asInt()).isEqualTo(2026);
        assertThat(deleted.path("month").asInt()).isEqualTo(9);
        assertThat(deleted.path("currentYear").asInt()).isEqualTo(2026);
        assertThat(deleted.path("currentMonth").asInt()).isEqualTo(9);
        assertThat(deleted.path("remainingDaysIncludingToday").asInt()).isEqualTo(16);
        assertThat(deleted.path("hasAnyBudget").asBoolean()).isFalse();
        assertThat(deleted.path("status").asString()).isEqualTo("NOT_SET");
        assertThat(deleted.path("currency").isNull()).isTrue();
        assertThat(deleted.path("total").isNull()).isTrue();
        assertThat(deleted.path("paymentGroups")).isEmpty();
        assertThat(deleted.path("categories")).isEmpty();
        assertThat(deleted.path("missingRateCount").asInt()).isZero();
        assertThat(deleted.path("dailyAllowance").isNull()).isTrue();
        assertThat(budgetRowCount(MEMBER_A)).isZero();
        assertThat(allocationCount(MEMBER_A)).isZero();

        JsonNode again = data(remove(MEMBER_A, SEPTEMBER).andExpect(status().isOk()));
        assertThat(again).isEqualTo(deleted);
    }

    @Test
    @DisplayName("쓰기 범위는 2000-01 ~ 이번 달 + 12 다 — 지난 달도 쓰고, 범위 밖은 BUDGET_MONTH_OUT_OF_RANGE, 읽기는 범위 밖도 된다")
    void write_range_is_2000_01_to_twelve_months_ahead() throws Exception {
        save(MEMBER_A, AUGUST, KRW_1M)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.remainingDaysIncludingToday").isEmpty());
        remove(MEMBER_A, AUGUST).andExpect(status().isOk());
        save(MEMBER_A, YearMonth.of(2000, 1), KRW_1M).andExpect(status().isOk());
        save(MEMBER_A, YearMonth.of(2027, 9), KRW_1M).andExpect(status().isOk());
        assertThat(budgetRowCount(MEMBER_A)).isEqualTo(2);

        save(MEMBER_A, YearMonth.of(2027, 10), KRW_1M)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BUDGET_MONTH_OUT_OF_RANGE"));
        remove(MEMBER_A, YearMonth.of(2027, 10))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BUDGET_MONTH_OUT_OF_RANGE"));
        save(MEMBER_A, YearMonth.of(1999, 12), KRW_1M)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BUDGET_MONTH_OUT_OF_RANGE"));
        assertThat(budgetRowCount(MEMBER_A)).isEqualTo(2);

        JsonNode tooFar = read(MEMBER_A, YearMonth.of(2027, 10));
        assertThat(tooFar.path("status").asString()).isEqualTo("NOT_SET");
        assertThat(tooFar.path("currentYear").asInt()).isEqualTo(2026);
        assertThat(tooFar.path("currentMonth").asInt()).isEqualTo(9);
        assertThat(tooFar.path("remainingDaysIncludingToday").isNull()).isTrue();
        assertThat(read(MEMBER_A, SEPTEMBER).path("remainingDaysIncludingToday").asInt())
                .isEqualTo(16);
    }

    @Test
    @DisplayName("수입 카테고리 몫은 400 BUDGET_INVALID_ALLOCATION 이고 행이 생기지 않는다")
    void income_category_share_is_invalid_allocation() throws Exception {
        save(MEMBER_A, SEPTEMBER, categoryBudget(INCOME_CATEGORY_ID, 1000))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BUDGET_INVALID_ALLOCATION"));
        assertThat(budgetRowCount(MEMBER_A)).isZero();
    }

    @Test
    @DisplayName("hasAnyBudget 은 어느 달이든 행이 있으면 true 이고, 전부 지우면 false 다")
    void has_any_budget_counts_any_month() throws Exception {
        save(MEMBER_A, AUGUST, KRW_1M).andExpect(status().isOk());
        save(MEMBER_A, OCTOBER, """
                        {"currency":"KRW","totalAmount":0}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hasAnyBudget").value(true));

        JsonNode september = read(MEMBER_A, SEPTEMBER);
        assertThat(september.path("status").asString()).isEqualTo("NOT_SET");
        assertThat(september.path("hasAnyBudget").asBoolean()).isTrue();

        remove(MEMBER_A, AUGUST)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hasAnyBudget").value(true));
        remove(MEMBER_A, OCTOBER)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hasAnyBudget").value(false));
        assertThat(read(MEMBER_A, SEPTEMBER).path("hasAnyBudget").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("몫만 바뀐 저장·같은 값 재저장도 budget.updated_at 을 갱신한다 — 익명 정리의 활동 술어가 이 값을 본다")
    void every_successful_save_touches_budget_updated_at() throws Exception {
        String creditCard =
                """
                {"currency":"KRW","totalAmount":100000,
                 "paymentGroupAmounts":[{"paymentGroup":"CREDIT_CARD","amount":%d}]}""";
        save(MEMBER_A, SEPTEMBER, creditCard.formatted(50000)).andExpect(status().isOk());

        // auditing 은 서버 Clock 이 아니라 벽시계를 쓴다 — 저장 사이에 시간이 흐른 것을 행을 과거로 돌려 만든다.
        ageBudgetRows(MEMBER_A);
        save(MEMBER_A, SEPTEMBER, creditCard.formatted(60000)).andExpect(status().isOk());
        assertThat(budgetTouched(MEMBER_A)).isTrue();

        ageBudgetRows(MEMBER_A);
        save(MEMBER_A, SEPTEMBER, creditCard.formatted(60000)).andExpect(status().isOk());
        assertThat(budgetTouched(MEMBER_A)).isTrue();
    }

    @Test
    @DisplayName("전체 없는 세트는 BUDGET_TOTAL_REQUIRED, 범위 밖 금액은 BUDGET_INVALID_AMOUNT, 통화 없는 본문은 VALIDATION_ERROR")
    void invalid_amounts_are_rejected_with_codes() throws Exception {
        save(MEMBER_A, SEPTEMBER, """
                        {"currency":"KRW"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BUDGET_TOTAL_REQUIRED"));
        save(MEMBER_A, SEPTEMBER, """
                        {"currency":"KRW","totalAmount":100000000}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BUDGET_INVALID_AMOUNT"));
        save(MEMBER_A, SEPTEMBER, """
                        {"totalAmount":1000}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        assertThat(budgetRowCount(MEMBER_A)).isZero();
    }

    @Test
    @DisplayName("몫 합이 전체를 넘는 PUT 은 카테고리·결제수단 모두 400 BUDGET_ALLOCATION_EXCEEDS_TOTAL 이고 그 달은 그대로다")
    void allocation_over_total_is_rejected_and_keeps_row() throws Exception {
        String saved =
                """
                {"currency":"KRW","totalAmount":1000000,
                 "paymentGroupAmounts":[{"paymentGroup":"CREDIT_CARD","amount":600000}],
                 "categoryAmounts":[{"categoryId":%d,"amount":300000}]}"""
                        .formatted(EXPENSE_CATEGORY_ID);
        save(MEMBER_A, SEPTEMBER, saved).andExpect(status().isOk());
        JsonNode before = read(MEMBER_A, SEPTEMBER);

        save(
                        MEMBER_A,
                        SEPTEMBER,
                        """
                        {"currency":"KRW","totalAmount":1000000,
                         "categoryAmounts":[{"categoryId":%d,"amount":600000},{"categoryId":2,"amount":400001}]}"""
                                .formatted(EXPENSE_CATEGORY_ID))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BUDGET_ALLOCATION_EXCEEDS_TOTAL"));
        assertThat(read(MEMBER_A, SEPTEMBER)).isEqualTo(before);

        save(
                        MEMBER_A,
                        SEPTEMBER,
                        """
                        {"currency":"KRW","totalAmount":1000000,
                         "paymentGroupAmounts":[{"paymentGroup":"CREDIT_CARD","amount":600000},
                                                {"paymentGroup":"CASH_AND_DEBIT","amount":400001}]}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BUDGET_ALLOCATION_EXCEEDS_TOTAL"));
        assertThat(read(MEMBER_A, SEPTEMBER)).isEqualTo(before);
        assertThat(allocationCount(MEMBER_A)).isEqualTo(2);
    }

    @Test
    @DisplayName("그 외 카테고리 줄 — 몫은 전체 − 카테고리 몫, 쓴 돈은 몫 없는 카테고리 거래다. 미설정 달이면 null")
    void other_categories_line_is_returned() throws Exception {
        createEntry(MEMBER_A, "100000", "KRW", EXPENSE_CATEGORY_ID, 1, "2026-09-10");
        createEntry(MEMBER_A, "50000", "KRW", 2, 1, "2026-09-11");
        createEntry(MEMBER_A, "20000", "KRW", 3, 2, "2026-09-12");
        save(MEMBER_A, SEPTEMBER, categoryBudget(EXPENSE_CATEGORY_ID, 300000)).andExpect(status().isOk());

        JsonNode other = read(MEMBER_A, SEPTEMBER).path("otherCategories");

        assertThat(other.path("budgetAmount").decimalValue()).isEqualByComparingTo("700000");
        assertThat(other.path("actualAmount").decimalValue()).isEqualByComparingTo("70000");
        assertThat(other.path("status").asString()).isEqualTo("IN_PROGRESS");
        assertThat(other.path("percent").asInt()).isEqualTo(10);
        assertThat(other.path("remainingAmount").decimalValue()).isEqualByComparingTo("630000");
        assertThat(other.path("overAmount").isNull()).isTrue();

        JsonNode notSet = read(MEMBER_A, OCTOBER);
        assertThat(notSet.path("status").asString()).isEqualTo("NOT_SET");
        assertThat(notSet.has("otherCategories")).isTrue();
        assertThat(notSet.path("otherCategories").isNull()).isTrue();
    }

    @Test
    @DisplayName("v1 이 남긴 몫 합 > 전체 행도 읽힌다 — 그 외 카테고리는 쓴 돈만 있는 줄이다")
    void legacy_row_with_shares_over_total_is_readable() throws Exception {
        createEntry(MEMBER_A, "30000", "KRW", 3, 1, "2026-09-10"); // 몫 없는 카테고리
        // 저장 API 는 합계 검사로 막으므로 JDBC 로만 넣을 수 있다. 결제수단 몫 합 > 전체는 V16 의
        // ck_budget_payment_groups_within_total 이 막아 남을 수 없으므로 카테고리 몫만 넘긴다.
        Long budgetId = jdbcTemplate.queryForObject(
                "insert into budget (member_id, month, currency_code, total_amount, credit_card_amount,"
                        + " created_at, updated_at)"
                        + " values (?, date '2026-09-01', 'KRW', 100000, 100000, now(), now()) returning id",
                Long.class,
                MEMBER_A);
        jdbcTemplate.update(
                "insert into budget_category_allocation (budget_id, category_id, amount)"
                        + " values (?, ?, 60000), (?, 2, 60000)",
                budgetId,
                EXPENSE_CATEGORY_ID,
                budgetId);

        JsonNode budget = read(MEMBER_A, SEPTEMBER);

        assertThat(budget.path("total").path("budgetAmount").decimalValue()).isEqualByComparingTo("100000");
        assertThat(budget.path("total").path("actualAmount").decimalValue()).isEqualByComparingTo("30000");
        assertThat(budget.path("total").path("status").asString()).isEqualTo("IN_PROGRESS");
        assertThat(budget.path("categories")).hasSize(2);
        JsonNode other = budget.path("otherCategories");
        assertThat(other.path("budgetAmount").isNull()).isTrue();
        assertThat(other.path("status").isNull()).isTrue();
        assertThat(other.path("actualAmount").decimalValue()).isEqualByComparingTo("30000");
    }

    @Test
    @DisplayName("외화(USD) 예산은 KRW·USD 거래를 tts 로 환산하고, 환율이 없는 날의 거래는 빼고 센다")
    void foreign_budget_converts_krw_transactions_by_tts_timeline() throws Exception {
        jdbcTemplate.update(
                """
                insert into exchange_rate (currency_code, tts, base_date, created_at, updated_at)
                values ('USD', 1300.000000, date '2026-09-02', now(), now())
                """);
        createEntry(MEMBER_A, "13000", "KRW", EXPENSE_CATEGORY_ID, 1, "2026-09-10");
        createEntry(MEMBER_A, "10.00", "USD", EXPENSE_CATEGORY_ID, 2, "2026-09-10");
        createEntry(MEMBER_A, "5000", "KRW", 2, 4, "2026-09-01"); // 9/1 이하 USD 환율 없음 → 제외
        save(
                        MEMBER_A,
                        SEPTEMBER,
                        """
                        {"currency":"USD","totalAmount":100.00,
                         "categoryAmounts":[{"categoryId":%d,"amount":50.00}]}"""
                                .formatted(EXPENSE_CATEGORY_ID))
                .andExpect(status().isOk());

        JsonNode budget = read(MEMBER_A, SEPTEMBER);

        assertThat(budget.path("currency").asString()).isEqualTo("USD");
        assertThat(budget.path("missingRateCount").asInt()).isEqualTo(1);
        assertThat(budget.path("total").path("actualAmount").decimalValue()).isEqualByComparingTo("20.00");
        assertThat(budget.path("total").path("status").asString()).isEqualTo("IN_PROGRESS");
        assertThat(budget.path("total").path("percent").asInt()).isEqualTo(20);
        assertThat(budget.path("status").asString()).isEqualTo("IN_PROGRESS");
        assertThat(budget.path("categories").get(0).path("actualAmount").decimalValue())
                .isEqualByComparingTo("20.00");
        assertThat(budget.path("otherCategories").path("actualAmount").decimalValue())
                .isEqualByComparingTo("0");
        assertThat(budget.path("paymentGroups").get(0).path("actualAmount").decimalValue())
                .isEqualByComparingTo("10.00");
        assertThat(budget.path("paymentGroups").get(1).path("actualAmount").decimalValue())
                .isEqualByComparingTo("10.00");
        assertThat(budget.path("paymentGroups").get(2).path("actualAmount").decimalValue())
                .isEqualByComparingTo("0");
        // (100 − 20) ÷ 16일 = 5.00
        assertThat(budget.path("dailyAllowance").path("amount").decimalValue()).isEqualByComparingTo("5.00");
        assertThat(budget.path("dailyAllowance").path("exceeded").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("같은 회원의 advisory lock 을 다른 트랜잭션이 쥐고 있으면 저장·삭제가 기다렸다가 둘 다 성공한다")
    void writes_wait_for_member_advisory_lock_then_both_succeed() throws Exception {
        budgetService.save(MEMBER_A, SEPTEMBER, krwRequest("1000"));
        budgetService.save(MEMBER_A, OCTOBER, krwRequest("1000"));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (PreparedStatement lock =
                    holder.prepareStatement("select 1 from pg_advisory_xact_lock(hashtext('budget:' || ?))")) {
                lock.setString(1, MEMBER_A.toString());
                lock.executeQuery().close();
            }
            CompletableFuture<Void> save = CompletableFuture.runAsync(
                    () -> budgetService.save(MEMBER_A, SEPTEMBER, krwRequest("2000")), executor);
            CompletableFuture<Void> delete =
                    CompletableFuture.runAsync(() -> budgetService.delete(MEMBER_A, OCTOBER), executor);

            // 시간이 아니라 DB 상태로 판정한다 — 저장과 삭제가 둘 다 실제로 advisory lock 을 기다리는 중이어야 한다.
            // 어느 한쪽이라도 락을 잡지 않으면 대기자가 2 가 되지 않는다.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            long waiting = 0;
            while (System.nanoTime() < deadline) {
                waiting = jdbcTemplate.queryForObject(
                        "select count(*) from pg_locks where locktype = 'advisory' and not granted", Long.class);
                if (waiting == 2) {
                    break;
                }
                Thread.sleep(20);
            }
            assertThat(waiting).isEqualTo(2);
            assertThat(save).isNotDone();
            assertThat(delete).isNotDone();

            holder.rollback();
            CompletableFuture.allOf(save, delete).get(30, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
        assertThat(jdbcTemplate.queryForList(
                        "select cast(month as text) || ':' || total_amount from budget where member_id = ?",
                        String.class,
                        MEMBER_A))
                .containsExactly("2026-09-01:2000.00");
    }

    @Test
    @DisplayName("본인이 삭제(비활성)한 커스텀 카테고리로 PUT 하면 404 CATEGORY_NOT_FOUND")
    void deactivated_own_category_is_not_found() throws Exception {
        long categoryId = createCustomCategory(MEMBER_A, "반려견");
        deleteCustomCategory(MEMBER_A, categoryId);

        save(MEMBER_A, SEPTEMBER, categoryBudget(categoryId, 300000))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CATEGORY_NOT_FOUND"));
        assertThat(budgetRowCount(MEMBER_A)).isZero();
    }

    @Test
    @DisplayName("지난 달 몫에 있던 카테고리를 삭제한 뒤 GET 하면 그 줄은 deleted:true 이고 이름이 채워져 있다")
    void past_month_line_of_deactivated_category_is_marked_deleted() throws Exception {
        long categoryId = createCustomCategory(MEMBER_A, "반려견");
        save(MEMBER_A, AUGUST, categoryBudget(categoryId, 300000)).andExpect(status().isOk());
        deleteCustomCategory(MEMBER_A, categoryId);

        JsonNode line = read(MEMBER_A, AUGUST).path("categories").get(0);

        assertThat(line.path("deleted").asBoolean()).isTrue();
        assertThat(line.path("category").path("id").asLong()).isEqualTo(categoryId);
        assertThat(line.path("category").path("displayNameKo").asString()).isEqualTo("반려견");
        assertThat(line.path("budgetAmount").decimalValue()).isEqualByComparingTo("300000");
    }

    @Test
    @DisplayName("categoryAmounts 가 100개를 넘으면 400 VALIDATION_ERROR")
    void too_many_category_amounts_is_validation_error() throws Exception {
        String items = IntStream.rangeClosed(1, 101)
                .mapToObj(i -> "{\"categoryId\":%d,\"amount\":1}".formatted(i))
                .collect(Collectors.joining(","));
        save(
                        MEMBER_A,
                        SEPTEMBER,
                        """
                        {"currency":"KRW","totalAmount":1000000,"categoryAmounts":[%s]}"""
                                .formatted(items))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        assertThat(budgetRowCount(MEMBER_A)).isZero();
    }

    @Test
    @DisplayName("회원이 삭제된 뒤 남은 JWT 로 PUT 하면 fk_budget_member 가 500 이 아니라 401 로 매핑된다")
    void write_after_member_deletion_is_unauthorized() throws Exception {
        jdbcTemplate.update("delete from auth.users where id = ?", MEMBER_A);

        save(MEMBER_A, SEPTEMBER, KRW_1M)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        assertThat(budgetRowCount(MEMBER_A)).isZero();
    }

    private ResultActions save(UUID memberId, YearMonth month, String body) throws Exception {
        return mockMvc.perform(put("/api/v1/budgets")
                .with(memberJwt(memberId))
                .param("year", String.valueOf(month.getYear()))
                .param("month", String.valueOf(month.getMonthValue()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions remove(UUID memberId, YearMonth month) throws Exception {
        return mockMvc.perform(delete("/api/v1/budgets")
                .with(memberJwt(memberId))
                .param("year", String.valueOf(month.getYear()))
                .param("month", String.valueOf(month.getMonthValue())));
    }

    private JsonNode read(UUID memberId, YearMonth month) throws Exception {
        return data(mockMvc.perform(get("/api/v1/budgets")
                        .with(memberJwt(memberId))
                        .param("year", String.valueOf(month.getYear()))
                        .param("month", String.valueOf(month.getMonthValue())))
                .andExpect(status().isOk()));
    }

    private JsonNode data(ResultActions result) throws Exception {
        return objectMapper
                .readTree(result.andReturn().getResponse().getContentAsString())
                .path("data");
    }

    private long createCustomCategory(UUID memberId, String name) throws Exception {
        return data(mockMvc.perform(post("/api/v1/categories/custom")
                                .with(memberJwt(memberId))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(
                                        Map.of("transactionType", "EXPENSE", "name", name))))
                        .andExpect(status().isOk()))
                .path("id")
                .asLong();
    }

    private void deleteCustomCategory(UUID memberId, long categoryId) throws Exception {
        mockMvc.perform(delete("/api/v1/categories/custom/{id}", categoryId).with(memberJwt(memberId)))
                .andExpect(status().isOk());
    }

    private void createEntry(UUID memberId, String amount, String currency, long categoryId, long assetId, String date)
            throws Exception {
        mockMvc.perform(post("/api/v1/ledgers")
                        .with(memberJwt(memberId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {"amount":%s,"currencyCode":"%s","categoryId":%d,"assetId":%d,"transactionDate":"%s"}"""
                                        .formatted(amount, currency, categoryId, assetId, date)))
                .andExpect(status().isOk());
    }

    /** total · 결제수단 그룹 3개 · 첫 카테고리 · 그 외 카테고리의 actualAmount 를 이 순서로. */
    private static List<BigDecimal> actualAmounts(JsonNode budget) {
        JsonNode groups = budget.path("paymentGroups");
        assertThat(groups).hasSize(3);
        return List.of(
                budget.path("total").path("actualAmount").decimalValue(),
                groups.get(0).path("actualAmount").decimalValue(),
                groups.get(1).path("actualAmount").decimalValue(),
                groups.get(2).path("actualAmount").decimalValue(),
                budget.path("categories").get(0).path("actualAmount").decimalValue(),
                budget.path("otherCategories").path("actualAmount").decimalValue());
    }

    private static String categoryBudget(long categoryId, long amount) {
        return """
                {"currency":"KRW","totalAmount":1000000,
                 "categoryAmounts":[{"categoryId":%d,"amount":%d}]}"""
                .formatted(categoryId, amount);
    }

    private long budgetRowCount(UUID memberId) {
        return jdbcTemplate.queryForObject("select count(*) from budget where member_id = ?", Long.class, memberId);
    }

    private void ageBudgetRows(UUID memberId) {
        jdbcTemplate.update(
                "update budget set updated_at = timestamp '2000-01-01 00:00:00' where member_id = ?", memberId);
    }

    /** 회원의 예산 행이 1개이고 그 updated_at 이 {@link #ageBudgetRows} 이후로 갱신됐는가. */
    private boolean budgetTouched(UUID memberId) {
        return jdbcTemplate.queryForObject(
                "select bool_and(updated_at > timestamp '2000-01-01 00:00:00') and count(*) = 1"
                        + " from budget where member_id = ?",
                Boolean.class,
                memberId);
    }

    private long allocationCount(UUID memberId) {
        return jdbcTemplate.queryForObject(
                """
                select (select count(*) from budget_category_allocation a join budget b on b.id = a.budget_id
                        where b.member_id = ?)
                     + (select count(credit_card_amount) + count(cash_and_debit_amount) + count(account_and_other_amount)
                        from budget where member_id = ?)
                """,
                Long.class,
                memberId,
                memberId);
    }

    private static SaveBudgetRequest krwRequest(String total) {
        return new SaveBudgetRequest(
                CurrencyCode.KRW,
                new BigDecimal(total),
                List.of(new SaveBudgetRequest.PaymentGroupAmountRequest(
                        PaymentGroup.CREDIT_CARD, new BigDecimal("500"))),
                List.of());
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

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
import com.self.multi_currency_household_ledger.ledger.domain.BudgetApplyTo;
import com.self.multi_currency_household_ledger.ledger.domain.PaymentGroup;
import com.self.multi_currency_household_ledger.ledger.domain.TransactionType;
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

/** 월 예산 API — 서버 Clock 은 2026-09-15(KST) 로 고정한다. 이번 달 = 9월, 쓸 수 있는 달 = 9·10월. */
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
    private static final YearMonth SEPTEMBER = YearMonth.of(2026, 9);
    private static final YearMonth OCTOBER = YearMonth.of(2026, 10);

    // 시드 카탈로그: 1 = 지출 카테고리, 14 = 수입 카테고리
    private static final long EXPENSE_CATEGORY_ID = 1L;
    private static final long INCOME_CATEGORY_ID = 14L;

    private static final String KRW_1M =
            """
            {"applyTo":"%s","amounts":{"currency":"KRW","totalAmount":1000000}}""";

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
    @DisplayName("예산 읽기·쓰기·되돌리기는 인증 없이는 모두 401 이다")
    void budget_endpoints_require_authentication() throws Exception {
        mockMvc.perform(get("/api/v1/budgets").param("year", "2026").param("month", "9"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(put("/api/v1/budgets/EXPENSE")
                        .param("year", "2026")
                        .param("month", "9")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(KRW_1M.formatted("THIS_MONTH")))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/v1/budgets/EXPENSE/month-value")
                        .param("year", "2026")
                        .param("month", "9"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("A 가 저장한 이번 달 지출 예산은 B 의 같은 달 GET 에 보이지 않는다 (IDOR)")
    void other_member_budget_is_not_visible() throws Exception {
        save(MEMBER_A, "EXPENSE", SEPTEMBER, KRW_1M.formatted("THIS_MONTH")).andExpect(status().isOk());

        JsonNode memberB = read(MEMBER_B, SEPTEMBER);
        assertThat(memberB.path("expense").path("source").asString()).isEqualTo("NOT_SET");
        assertThat(memberB.path("expense").path("status").asString()).isEqualTo("NOT_SET");
        assertThat(memberB.path("hasAnyBudget").asBoolean()).isFalse();

        JsonNode memberA = read(MEMBER_A, SEPTEMBER);
        assertThat(memberA.path("expense").path("source").asString()).isEqualTo("MONTH_VALUE");
        assertThat(memberA.path("hasAnyBudget").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("B 가 A 의 커스텀 카테고리 id 로 PUT 하면 404 CATEGORY_NOT_FOUND 이고 A 의 행은 그대로다 (IDOR)")
    void other_member_custom_category_is_not_found() throws Exception {
        long memberACategoryId = createCustomCategory(MEMBER_A, "반려견");
        String body = categoryBudget("THIS_MONTH", memberACategoryId, 300000);
        save(MEMBER_A, "EXPENSE", SEPTEMBER, body).andExpect(status().isOk());

        save(MEMBER_B, "EXPENSE", SEPTEMBER, body)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CATEGORY_NOT_FOUND"));

        assertThat(budgetRowCount(MEMBER_B)).isZero();
        JsonNode memberA = read(MEMBER_A, SEPTEMBER).path("expense");
        assertThat(memberA.path("categories")).hasSize(1);
        assertThat(memberA.path("categories").get(0).path("category").path("id").asLong())
                .isEqualTo(memberACategoryId);
        assertThat(memberA.path("categories").get(0).path("budgetAmount").decimalValue())
                .isEqualByComparingTo("300000");
    }

    @Test
    @DisplayName("B 의 DELETE month-value 는 A 의 (MONTH, M) 행을 지우지 않는다 (IDOR)")
    void other_member_revert_keeps_rows() throws Exception {
        save(MEMBER_A, "EXPENSE", SEPTEMBER, KRW_1M.formatted("THIS_MONTH")).andExpect(status().isOk());

        revert(MEMBER_B, "EXPENSE", SEPTEMBER).andExpect(status().isOk());

        assertThat(budgetRowCount(MEMBER_A)).isEqualTo(1);
        assertThat(read(MEMBER_A, SEPTEMBER).path("expense").path("source").asString())
                .isEqualTo("MONTH_VALUE");
    }

    @Test
    @DisplayName("DELETE month-value 는 내 (MONTH, M) 행을 지워 기본값으로 되돌리고, 되돌릴 값이 없어도 성공한다")
    void revert_removes_month_value() throws Exception {
        save(MEMBER_A, "EXPENSE", SEPTEMBER, KRW_1M.formatted("FROM_THIS_MONTH"))
                .andExpect(status().isOk());
        save(MEMBER_A, "EXPENSE", SEPTEMBER, offBody("THIS_MONTH")).andExpect(status().isOk());

        revert(MEMBER_A, "EXPENSE", SEPTEMBER)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.expense.source").value("DEFAULT"));
        revert(MEMBER_A, "EXPENSE", SEPTEMBER)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.expense.source").value("DEFAULT"));
    }

    @Test
    @DisplayName("지난 달 쓰기는 BUDGET_PAST_MONTH, 다다음 달은 BUDGET_MONTH_TOO_FAR, 다음 달 쓰기와 지난 달 읽기는 된다")
    void write_window_is_this_and_next_month() throws Exception {
        save(MEMBER_A, "EXPENSE", YearMonth.of(2026, 8), KRW_1M.formatted("THIS_MONTH"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BUDGET_PAST_MONTH"));
        revert(MEMBER_A, "EXPENSE", YearMonth.of(2026, 8))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BUDGET_PAST_MONTH"));
        save(MEMBER_A, "EXPENSE", YearMonth.of(2026, 11), KRW_1M.formatted("THIS_MONTH"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BUDGET_MONTH_TOO_FAR"));
        assertThat(budgetRowCount(MEMBER_A)).isZero();

        save(MEMBER_A, "EXPENSE", OCTOBER, KRW_1M.formatted("THIS_MONTH"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.editable").value(true))
                .andExpect(jsonPath("$.data.remainingDaysIncludingToday").isEmpty());

        JsonNode august = read(MEMBER_A, YearMonth.of(2026, 8));
        assertThat(august.path("editable").asBoolean()).isFalse();
        assertThat(august.path("currentYear").asInt()).isEqualTo(2026);
        assertThat(august.path("currentMonth").asInt()).isEqualTo(9);
        assertThat(august.path("remainingDaysIncludingToday").isNull()).isTrue();

        JsonNode september = read(MEMBER_A, SEPTEMBER);
        assertThat(september.path("editable").asBoolean()).isTrue();
        assertThat(september.path("remainingDaysIncludingToday").asInt()).isEqualTo(16);
    }

    @Test
    @DisplayName("FROM_THIS_MONTH 는 늦은 DEFAULT 와 M 의 MONTH 만 지우고, 이른 DEFAULT 와 M+1 의 MONTH 는 남긴다")
    void from_this_month_deletes_later_defaults_and_this_month_value_only() throws Exception {
        jdbcTemplate.update(
                """
                insert into budget (member_id, axis, kind, month, currency_code, total_amount)
                values (?, 'EXPENSE', 'DEFAULT', date '2026-08-01', 'KRW', 500000)
                """,
                MEMBER_A);
        save(MEMBER_A, "EXPENSE", OCTOBER, KRW_1M.formatted("FROM_THIS_MONTH")).andExpect(status().isOk());
        save(MEMBER_A, "EXPENSE", SEPTEMBER, KRW_1M.formatted("THIS_MONTH")).andExpect(status().isOk());
        save(MEMBER_A, "EXPENSE", OCTOBER, KRW_1M.formatted("THIS_MONTH")).andExpect(status().isOk());

        save(
                        MEMBER_A,
                        "EXPENSE",
                        SEPTEMBER,
                        """
                        {"applyTo":"FROM_THIS_MONTH","amounts":{"currency":"KRW","totalAmount":700000}}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.expense.source").value("DEFAULT"))
                .andExpect(jsonPath("$.data.expense.total.budgetAmount").value(700000));

        assertThat(jdbcTemplate.queryForList(
                        "select kind || ':' || month from budget where member_id = ? order by kind, month",
                        String.class,
                        MEMBER_A))
                .containsExactly("DEFAULT:2026-08-01", "DEFAULT:2026-09-01", "MONTH:2026-10-01");
        assertThat(read(MEMBER_A, OCTOBER).path("expense").path("source").asString())
                .isEqualTo("MONTH_VALUE");
    }

    @Test
    @DisplayName("같은 몫 세트를 두 번 PUT 해도 둘 다 200 이고 결과가 같다 — 그다음 amounts:null 이면 MONTH_OFF")
    void same_allocation_set_is_idempotent_and_null_turns_off() throws Exception {
        String body =
                """
                {"applyTo":"THIS_MONTH","amounts":{"currency":"KRW","totalAmount":1000000,
                 "paymentGroupAmounts":[{"paymentGroup":"CREDIT_CARD","amount":600000}],
                 "categoryAmounts":[{"categoryId":%d,"amount":300000}]}}"""
                        .formatted(EXPENSE_CATEGORY_ID);

        JsonNode first = data(save(MEMBER_A, "EXPENSE", SEPTEMBER, body).andExpect(status().isOk()));
        JsonNode second = data(save(MEMBER_A, "EXPENSE", SEPTEMBER, body).andExpect(status().isOk()));

        assertThat(second).isEqualTo(first);
        assertThat(allocationCount(MEMBER_A)).isEqualTo(2);
        JsonNode expense = second.path("expense");
        assertThat(expense.path("paymentGroups")).hasSize(3);
        assertThat(expense.path("paymentGroups").get(0).path("paymentGroup").asString())
                .isEqualTo("CREDIT_CARD");
        assertThat(expense.path("paymentGroups").get(0).path("budgetAmount").decimalValue())
                .isEqualByComparingTo("600000");
        assertThat(expense.path("paymentGroups").get(1).path("budgetAmount").isNull())
                .isTrue();
        assertThat(expense.path("categories").get(0).path("deleted").asBoolean())
                .isFalse();
        assertThat(expense.path("paymentGroupUnallocatedAmount").decimalValue()).isEqualByComparingTo("400000");
        assertThat(expense.path("categoryUnallocatedAmount").decimalValue()).isEqualByComparingTo("700000");

        JsonNode off =
                data(save(MEMBER_A, "EXPENSE", SEPTEMBER, offBody("THIS_MONTH")).andExpect(status().isOk()));
        assertThat(off.path("expense").path("source").asString()).isEqualTo("MONTH_OFF");
        assertThat(off.path("expense").path("status").asString()).isEqualTo("OFF");
        assertThat(off.path("expense").path("currency").isNull()).isTrue();
        assertThat(off.path("expense").path("total").isNull()).isTrue();
        assertThat(off.path("expense").path("paymentGroups")).isEmpty();
        assertThat(off.path("expense").path("categories")).isEmpty();
        assertThat(allocationCount(MEMBER_A)).isZero();
    }

    @Test
    @DisplayName("다른 축의 카테고리 몫은 BUDGET_INVALID_ALLOCATION, 전체 없는 금액 세트는 BUDGET_TOTAL_REQUIRED")
    void invalid_amounts_are_rejected_with_codes() throws Exception {
        save(MEMBER_A, "EXPENSE", SEPTEMBER, categoryBudget("THIS_MONTH", INCOME_CATEGORY_ID, 1000))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BUDGET_INVALID_ALLOCATION"));
        save(
                        MEMBER_A,
                        "EXPENSE",
                        SEPTEMBER,
                        """
                        {"applyTo":"THIS_MONTH","amounts":{"currency":"KRW"}}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BUDGET_TOTAL_REQUIRED"));
        save(MEMBER_A, "EXPENSE", SEPTEMBER, """
                        {"amounts":null}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        assertThat(budgetRowCount(MEMBER_A)).isZero();
    }

    @Test
    @DisplayName("hasAnyBudget 은 끔 행만 있으면 false, 0원이라도 금액 세트가 있으면 true")
    void has_any_budget_ignores_off_rows() throws Exception {
        save(MEMBER_A, "EXPENSE", SEPTEMBER, offBody("THIS_MONTH")).andExpect(status().isOk());
        save(MEMBER_A, "INCOME", SEPTEMBER, offBody("FROM_THIS_MONTH")).andExpect(status().isOk());

        JsonNode offOnly = read(MEMBER_A, SEPTEMBER);
        assertThat(offOnly.path("hasAnyBudget").asBoolean()).isFalse();
        assertThat(offOnly.path("income").path("source").asString()).isEqualTo("DEFAULT_OFF");

        save(
                        MEMBER_A,
                        "INCOME",
                        OCTOBER,
                        """
                        {"applyTo":"THIS_MONTH","amounts":{"currency":"KRW","totalAmount":0}}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hasAnyBudget").value(true));
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
                        "EXPENSE",
                        SEPTEMBER,
                        """
                        {"applyTo":"THIS_MONTH","amounts":{"currency":"USD","totalAmount":100.00,
                         "categoryAmounts":[{"categoryId":%d,"amount":50.00}]}}"""
                                .formatted(EXPENSE_CATEGORY_ID))
                .andExpect(status().isOk());

        JsonNode expense = read(MEMBER_A, SEPTEMBER).path("expense");

        assertThat(expense.path("currency").asString()).isEqualTo("USD");
        assertThat(expense.path("missingRateCount").asInt()).isEqualTo(1);
        assertThat(expense.path("total").path("actualAmount").decimalValue()).isEqualByComparingTo("20.00");
        assertThat(expense.path("total").path("status").asString()).isEqualTo("IN_PROGRESS");
        assertThat(expense.path("total").path("percent").asInt()).isEqualTo(20);
        assertThat(expense.path("status").asString()).isEqualTo("IN_PROGRESS");
        assertThat(expense.path("categories").get(0).path("actualAmount").decimalValue())
                .isEqualByComparingTo("20.00");
        assertThat(expense.path("otherCategoriesActualAmount").decimalValue()).isEqualByComparingTo("0");
        assertThat(expense.path("paymentGroups").get(0).path("actualAmount").decimalValue())
                .isEqualByComparingTo("10.00");
        assertThat(expense.path("paymentGroups").get(1).path("actualAmount").decimalValue())
                .isEqualByComparingTo("10.00");
        assertThat(expense.path("paymentGroups").get(2).path("actualAmount").decimalValue())
                .isEqualByComparingTo("0");
        // (100 − 20) ÷ 16일 = 5.00
        assertThat(expense.path("dailyAllowance").path("amount").decimalValue()).isEqualByComparingTo("5.00");
        assertThat(expense.path("dailyAllowance").path("exceeded").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("같은 회원의 advisory lock 을 다른 트랜잭션이 쥐고 있으면 THIS_MONTH·FROM_THIS_MONTH 쓰기가 기다렸다가 둘 다 성공한다")
    void writes_wait_for_member_advisory_lock_then_both_succeed() throws Exception {
        SaveBudgetRequest.BudgetAmountsRequest amounts = krwAmounts("1000");
        budgetService.save(MEMBER_A, TransactionType.EXPENSE, SEPTEMBER, BudgetApplyTo.THIS_MONTH, amounts);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (PreparedStatement lock =
                    holder.prepareStatement("select 1 from pg_advisory_xact_lock(hashtext('budget:' || ?))")) {
                lock.setString(1, MEMBER_A.toString());
                lock.executeQuery().close();
            }
            CompletableFuture<Void> thisMonth = CompletableFuture.runAsync(
                    () -> budgetService.save(
                            MEMBER_A, TransactionType.EXPENSE, SEPTEMBER, BudgetApplyTo.THIS_MONTH, krwAmounts("2000")),
                    executor);
            CompletableFuture<Void> fromThisMonth = CompletableFuture.runAsync(
                    () -> budgetService.save(
                            MEMBER_A, TransactionType.EXPENSE, SEPTEMBER, BudgetApplyTo.FROM_THIS_MONTH, amounts),
                    executor);

            // 시간이 아니라 DB 상태로 판정한다 — 두 쓰기가 실제로 advisory lock 을 기다리는 중이어야 한다.
            // 락이 없으면 대기자가 생기지 않아 상한까지 2 가 되지 않는다.
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
            assertThat(thisMonth).isNotDone();
            assertThat(fromThisMonth).isNotDone();

            holder.rollback();
            CompletableFuture.allOf(thisMonth, fromThisMonth).get(30, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
        assertThat(jdbcTemplate.queryForObject(
                        "select count(*) from budget where member_id = ? and kind = 'DEFAULT'", Long.class, MEMBER_A))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("본인이 삭제(비활성)한 커스텀 카테고리로 PUT 하면 404 CATEGORY_NOT_FOUND")
    void deactivated_own_category_is_not_found() throws Exception {
        long categoryId = createCustomCategory(MEMBER_A, "반려견");
        deleteCustomCategory(MEMBER_A, categoryId);

        save(MEMBER_A, "EXPENSE", SEPTEMBER, categoryBudget("THIS_MONTH", categoryId, 300000))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CATEGORY_NOT_FOUND"));
        assertThat(budgetRowCount(MEMBER_A)).isZero();
    }

    @Test
    @DisplayName("지난 달 몫에 있던 카테고리를 삭제한 뒤 GET 하면 그 줄은 deleted:true 이고 이름이 채워져 있다")
    void past_month_line_of_deactivated_category_is_marked_deleted() throws Exception {
        long categoryId = createCustomCategory(MEMBER_A, "반려견");
        // 지난 달은 API 로 쓸 수 없어 행을 직접 넣는다.
        Long budgetId = jdbcTemplate.queryForObject(
                """
                insert into budget (member_id, axis, kind, month, currency_code, total_amount, created_at, updated_at)
                values (?, 'EXPENSE', 'MONTH', date '2026-08-01', 'KRW', 1000000, now(), now()) returning id
                """,
                Long.class,
                MEMBER_A);
        jdbcTemplate.update(
                "insert into budget_allocation (budget_id, category_id, amount) values (?, ?, 300000)",
                budgetId,
                categoryId);
        deleteCustomCategory(MEMBER_A, categoryId);

        JsonNode line = read(MEMBER_A, YearMonth.of(2026, 8))
                .path("expense")
                .path("categories")
                .get(0);

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
                        "EXPENSE",
                        SEPTEMBER,
                        """
                        {"applyTo":"THIS_MONTH","amounts":{"currency":"KRW","totalAmount":1000000,
                         "categoryAmounts":[%s]}}"""
                                .formatted(items))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        assertThat(budgetRowCount(MEMBER_A)).isZero();
    }

    @Test
    @DisplayName("회원이 삭제된 뒤 남은 JWT 로 PUT 하면 fk_budget_member 가 500 이 아니라 401 로 매핑된다")
    void write_after_member_deletion_is_unauthorized() throws Exception {
        jdbcTemplate.update("delete from auth.users where id = ?", MEMBER_A);

        save(MEMBER_A, "EXPENSE", SEPTEMBER, KRW_1M.formatted("THIS_MONTH"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        assertThat(budgetRowCount(MEMBER_A)).isZero();
    }

    private ResultActions save(UUID memberId, String axis, YearMonth month, String body) throws Exception {
        return mockMvc.perform(put("/api/v1/budgets/{axis}", axis)
                .with(memberJwt(memberId))
                .param("year", String.valueOf(month.getYear()))
                .param("month", String.valueOf(month.getMonthValue()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions revert(UUID memberId, String axis, YearMonth month) throws Exception {
        return mockMvc.perform(delete("/api/v1/budgets/{axis}/month-value", axis)
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

    private static String categoryBudget(String applyTo, long categoryId, long amount) {
        return """
                {"applyTo":"%s","amounts":{"currency":"KRW","totalAmount":1000000,
                 "categoryAmounts":[{"categoryId":%d,"amount":%d}]}}"""
                .formatted(applyTo, categoryId, amount);
    }

    private static String offBody(String applyTo) {
        return """
                {"applyTo":"%s","amounts":null}""".formatted(applyTo);
    }

    private long budgetRowCount(UUID memberId) {
        return jdbcTemplate.queryForObject("select count(*) from budget where member_id = ?", Long.class, memberId);
    }

    private long allocationCount(UUID memberId) {
        return jdbcTemplate.queryForObject(
                "select count(*) from budget_allocation a join budget b on b.id = a.budget_id where b.member_id = ?",
                Long.class,
                memberId);
    }

    private static SaveBudgetRequest.BudgetAmountsRequest krwAmounts(String total) {
        return new SaveBudgetRequest.BudgetAmountsRequest(
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

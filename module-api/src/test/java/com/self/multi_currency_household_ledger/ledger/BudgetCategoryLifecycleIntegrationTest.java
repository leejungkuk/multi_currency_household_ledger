package com.self.multi_currency_household_ledger.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.self.multi_currency_household_ledger.AuthUserFixture;
import com.self.multi_currency_household_ledger.ledger.service.CatalogService;
import com.self.multi_currency_household_ledger.ledger.service.LedgerPurgeService;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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

/** 카테고리 삭제·정리와 예산 몫 — 서버 Clock 은 2026-09-15(KST) 로 고정한다. 이번 달 C = 9월. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Import(BudgetCategoryLifecycleIntegrationTest.FixedClockConfig.class)
@TestPropertySource(
        properties = {
            "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://example.supabase.co/auth/v1",
            "exchange.eximbank.api-key=test-api-key",
            // 고정 시계라 레이트리밋 윈도가 롤오버되지 않는다 — 한도만 올린다.
            "woni.security.rate-limit.read-limit=1000",
            "woni.security.rate-limit.write-limit=1000"
        })
class BudgetCategoryLifecycleIntegrationTest {

    private static final UUID MEMBER_A = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID MEMBER_B = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final YearMonth AUGUST = YearMonth.of(2026, 8);
    private static final YearMonth SEPTEMBER = YearMonth.of(2026, 9);
    private static final YearMonth OCTOBER = YearMonth.of(2026, 10);

    // 시드 카탈로그: 1 = 지출 카테고리
    private static final long SYSTEM_CATEGORY_ID = 1L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CatalogService catalogService;

    @Autowired
    private LedgerPurgeService ledgerPurgeService;

    @Autowired
    private DataSource dataSource;

    @MockitoBean
    @SuppressWarnings("UnusedVariable")
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void setUp() {
        new AuthUserFixture(jdbcTemplate).reset(MEMBER_A, MEMBER_B);
    }

    @Test
    @DisplayName("8월 DEFAULT 에 X 몫이 있고 오늘이 9월일 때 X 를 지우면 8월 해석·행은 그대로, 9·10월 해석에서 X 가 빠진다")
    void deleting_category_keeps_past_default_and_drops_it_from_this_month() throws Exception {
        long x = createCustomCategory(MEMBER_A, "반려견");
        long august = insertBudget(MEMBER_A, "DEFAULT", AUGUST);
        insertAllocation(august, x, 300000);
        insertAllocation(august, SYSTEM_CATEGORY_ID, 100000);

        deleteCustomCategory(MEMBER_A, x).andExpect(status().isOk());

        assertThat(categoryIds(read(MEMBER_A, AUGUST))).containsExactlyInAnyOrder(SYSTEM_CATEGORY_ID, x);
        assertThat(categoryIds(read(MEMBER_A, SEPTEMBER))).containsExactly(SYSTEM_CATEGORY_ID);
        assertThat(categoryIds(read(MEMBER_A, OCTOBER))).containsExactly(SYSTEM_CATEGORY_ID);
        assertThat(read(MEMBER_A, SEPTEMBER).path("source").asString()).isEqualTo("DEFAULT");
        assertThat(read(MEMBER_A, SEPTEMBER).path("total").path("budgetAmount").decimalValue())
                .isEqualByComparingTo("1000000");
        assertThat(allocatedCategories(august)).containsExactlyInAnyOrder(SYSTEM_CATEGORY_ID, x);
        assertThat(budgetMonths(MEMBER_A, "DEFAULT")).containsExactly("2026-08-01", "2026-09-01");
        assertThat(isActive(x)).isFalse();
    }

    @Test
    @DisplayName("8월 DEFAULT 와 9월 MONTH 에 X 몫이 있으면 9월 MONTH 에서 X 가 빠지고 X 없는 (DEFAULT, 9월) 이 생겨 10월에도 X 가 없다")
    void deleting_category_with_this_month_value_still_splits_default() throws Exception {
        long x = createCustomCategory(MEMBER_A, "반려견");
        long august = insertBudget(MEMBER_A, "DEFAULT", AUGUST);
        insertAllocation(august, x, 300000);
        insertAllocation(august, SYSTEM_CATEGORY_ID, 100000);
        long septemberValue = insertBudget(MEMBER_A, "MONTH", SEPTEMBER);
        insertAllocation(septemberValue, x, 200000);

        deleteCustomCategory(MEMBER_A, x).andExpect(status().isOk());

        assertThat(allocatedCategories(septemberValue)).isEmpty();
        assertThat(read(MEMBER_A, SEPTEMBER).path("source").asString()).isEqualTo("MONTH_VALUE");
        assertThat(budgetMonths(MEMBER_A, "DEFAULT")).containsExactly("2026-08-01", "2026-09-01");
        assertThat(allocatedCategories(budgetId(MEMBER_A, "DEFAULT", SEPTEMBER)))
                .containsExactly(SYSTEM_CATEGORY_ID);
        assertThat(read(MEMBER_A, OCTOBER).path("source").asString()).isEqualTo("DEFAULT");
        assertThat(categoryIds(read(MEMBER_A, OCTOBER))).containsExactly(SYSTEM_CATEGORY_ID);
        assertThat(categoryIds(read(MEMBER_A, AUGUST))).containsExactlyInAnyOrder(SYSTEM_CATEGORY_ID, x);
        assertThat(allocatedCategories(august)).containsExactlyInAnyOrder(SYSTEM_CATEGORY_ID, x);
    }

    @Test
    @DisplayName("8월 DEFAULT 와 (DEFAULT, 10월) 에 X 몫이 있고 (DEFAULT, 9월) 이 없으면 X 없는 (DEFAULT, 9월) 이 생기고 10월 행에서 X 가 빠진다")
    void deleting_category_with_later_default_splits_this_month_and_detaches_later() throws Exception {
        long x = createCustomCategory(MEMBER_A, "반려견");
        long august = insertBudget(MEMBER_A, "DEFAULT", AUGUST);
        insertAllocation(august, x, 300000);
        insertAllocation(august, SYSTEM_CATEGORY_ID, 100000);
        long october = insertBudget(MEMBER_A, "DEFAULT", OCTOBER);
        insertAllocation(october, x, 200000);
        insertAllocation(october, SYSTEM_CATEGORY_ID, 50000);

        deleteCustomCategory(MEMBER_A, x).andExpect(status().isOk());

        assertThat(budgetMonths(MEMBER_A, "DEFAULT")).containsExactly("2026-08-01", "2026-09-01", "2026-10-01");
        assertThat(allocatedCategories(budgetId(MEMBER_A, "DEFAULT", SEPTEMBER)))
                .containsExactly(SYSTEM_CATEGORY_ID);
        assertThat(allocatedCategories(october)).containsExactly(SYSTEM_CATEGORY_ID);
        assertThat(allocatedCategories(august)).containsExactlyInAnyOrder(SYSTEM_CATEGORY_ID, x);
        assertThat(categoryIds(read(MEMBER_A, SEPTEMBER))).containsExactly(SYSTEM_CATEGORY_ID);
        assertThat(categoryIds(read(MEMBER_A, AUGUST))).containsExactlyInAnyOrder(SYSTEM_CATEGORY_ID, x);
    }

    @Test
    @DisplayName("수입 축 — 8월 DEFAULT 에 수입 카테고리 X 몫이 있으면 X 삭제 뒤 8월 해석은 그대로, 9·10월 해석에서 X 가 빠진다")
    void deleting_income_category_keeps_past_default_and_drops_it_from_this_month() throws Exception {
        long x = createCustomCategory(MEMBER_A, "INCOME", "부업");
        long y = createCustomCategory(MEMBER_A, "INCOME", "이자");
        long august = insertBudget(MEMBER_A, "INCOME", "DEFAULT", AUGUST);
        insertAllocation(august, x, 300000);
        insertAllocation(august, y, 100000);

        deleteCustomCategory(MEMBER_A, x).andExpect(status().isOk());

        assertThat(categoryIds(read(MEMBER_A, "income", AUGUST))).containsExactlyInAnyOrder(x, y);
        assertThat(categoryIds(read(MEMBER_A, "income", SEPTEMBER))).containsExactly(y);
        assertThat(categoryIds(read(MEMBER_A, "income", OCTOBER))).containsExactly(y);
        assertThat(allocatedCategories(august)).containsExactlyInAnyOrder(x, y);
        assertThat(allocatedCategories(budgetId(MEMBER_A, "DEFAULT", SEPTEMBER)))
                .containsExactly(y);
        assertThat(budgetMonths(MEMBER_A, "DEFAULT")).containsExactly("2026-08-01", "2026-09-01");
    }

    @Test
    @DisplayName("C 이전 DEFAULT 에 그 카테고리 몫이 없으면 (DEFAULT, C) 를 새로 만들지 않는다")
    void deleting_category_absent_from_past_default_creates_no_row() throws Exception {
        long x = createCustomCategory(MEMBER_A, "반려견");
        long august = insertBudget(MEMBER_A, "DEFAULT", AUGUST);
        insertAllocation(august, SYSTEM_CATEGORY_ID, 100000);

        deleteCustomCategory(MEMBER_A, x).andExpect(status().isOk());

        assertThat(budgetMonths(MEMBER_A, "DEFAULT")).containsExactly("2026-08-01");
        assertThat(read(MEMBER_A, SEPTEMBER).path("source").asString()).isEqualTo("DEFAULT");
    }

    @Test
    @DisplayName("(DEFAULT, C) 가 이미 있으면 새 행 없이 그 행에서 X 몫만 지우고 옛 DEFAULT 는 그대로다")
    void deleting_category_with_existing_this_month_default_only_detaches() throws Exception {
        long x = createCustomCategory(MEMBER_A, "반려견");
        long august = insertBudget(MEMBER_A, "DEFAULT", AUGUST);
        insertAllocation(august, x, 300000);
        long september = insertBudget(MEMBER_A, "DEFAULT", SEPTEMBER);
        insertAllocation(september, x, 200000);
        insertAllocation(september, SYSTEM_CATEGORY_ID, 100000);

        deleteCustomCategory(MEMBER_A, x).andExpect(status().isOk());

        assertThat(budgetMonths(MEMBER_A, "DEFAULT")).containsExactly("2026-08-01", "2026-09-01");
        assertThat(allocatedCategories(september)).containsExactly(SYSTEM_CATEGORY_ID);
        assertThat(allocatedCategories(august)).containsExactly(x);
    }

    @Test
    @DisplayName("9월 MONTH·10월 MONTH 의 X 몫은 지워지고 8월 MONTH 의 X 몫은 남는다")
    void deleting_category_detaches_this_and_later_month_values_only() throws Exception {
        long x = createCustomCategory(MEMBER_A, "반려견");
        long august = insertBudget(MEMBER_A, "MONTH", AUGUST);
        insertAllocation(august, x, 300000);
        save(MEMBER_A, SEPTEMBER, categoryBudget("THIS_MONTH", x)).andExpect(status().isOk());
        save(MEMBER_A, OCTOBER, categoryBudget("THIS_MONTH", x)).andExpect(status().isOk());

        deleteCustomCategory(MEMBER_A, x).andExpect(status().isOk());

        assertThat(categoryIds(read(MEMBER_A, SEPTEMBER))).isEmpty();
        assertThat(categoryIds(read(MEMBER_A, OCTOBER))).isEmpty();
        assertThat(read(MEMBER_A, SEPTEMBER).path("source").asString()).isEqualTo("MONTH_VALUE");
        assertThat(categoryIds(read(MEMBER_A, AUGUST))).containsExactly(x);
        assertThat(allocatedCategories(august)).containsExactly(x);
    }

    @Test
    @DisplayName("같은 8월 DEFAULT 에 몫이 있는 두 카테고리를 동시에 삭제해도 둘 다 성공하고 9월부터 둘 다 빠진다")
    void concurrent_deletes_of_two_categories_both_succeed() throws Exception {
        long x = createCustomCategory(MEMBER_A, "반려견");
        long y = createCustomCategory(MEMBER_A, "어학원");
        long august = insertBudget(MEMBER_A, "DEFAULT", AUGUST);
        insertAllocation(august, x, 300000);
        insertAllocation(august, y, 200000);
        insertAllocation(august, SYSTEM_CATEGORY_ID, 100000);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (PreparedStatement lock =
                    holder.prepareStatement("select 1 from pg_advisory_xact_lock(hashtext('budget:' || ?))")) {
                lock.setString(1, MEMBER_A.toString());
                lock.executeQuery().close();
            }
            CompletableFuture<Void> deleteX =
                    CompletableFuture.runAsync(() -> catalogService.deleteCustomCategory(MEMBER_A, x), executor);
            CompletableFuture<Void> deleteY =
                    CompletableFuture.runAsync(() -> catalogService.deleteCustomCategory(MEMBER_A, y), executor);

            // 두 삭제가 실제로 회원 advisory lock 을 기다리는 중이어야 한다 — 락이 없으면 대기자가 생기지 않는다.
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

            holder.rollback();
            CompletableFuture.allOf(deleteX, deleteY).get(30, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertThat(isActive(x)).isFalse();
        assertThat(isActive(y)).isFalse();
        assertThat(budgetMonths(MEMBER_A, "DEFAULT")).containsExactly("2026-08-01", "2026-09-01");
        assertThat(categoryIds(read(MEMBER_A, SEPTEMBER))).containsExactly(SYSTEM_CATEGORY_ID);
        assertThat(allocatedCategories(august)).containsExactlyInAnyOrder(x, y, SYSTEM_CATEGORY_ID);
    }

    @Test
    @DisplayName("카테고리 삭제는 몫보다 카테고리를 먼저 잠근다 — X 행 락을 기다리는 동안 budget_allocation 락이 없다")
    void deleting_category_locks_category_before_allocations() throws Exception {
        long x = createCustomCategory(MEMBER_A, "반려견");
        long august = insertBudget(MEMBER_A, "DEFAULT", AUGUST);
        insertAllocation(august, x, 300000);
        insertAllocation(august, SYSTEM_CATEGORY_ID, 100000);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (PreparedStatement lock = holder.prepareStatement("select 1 from category where id = ? for update")) {
                lock.setLong(1, x);
                lock.executeQuery().close();
            }
            CompletableFuture<Void> deleteX =
                    CompletableFuture.runAsync(() -> catalogService.deleteCustomCategory(MEMBER_A, x), executor);

            // 행 락 대기는 보유자 xid 에 대한 transactionid 대기로 드러난다. 몫을 먼저 지웠다면 그 pid 가 budget_allocation 락을 갖는다.
            Integer waiterPid = awaitWaiter("not granted and locktype = 'transactionid'");
            assertThat(waiterPid).isNotNull();
            assertThat(jdbcTemplate.queryForObject(
                            """
                            select count(*) from pg_locks
                            where pid = ? and relation = 'budget_allocation'::regclass
                            """,
                            Long.class,
                            waiterPid))
                    .isZero();

            holder.rollback();
            deleteX.get(30, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertThat(isActive(x)).isFalse();
        assertThat(allocatedCategories(budgetId(MEMBER_A, "DEFAULT", SEPTEMBER)))
                .containsExactly(SYSTEM_CATEGORY_ID);
    }

    @Test
    @DisplayName("카테고리 삭제는 카테고리보다 회원 advisory lock 을 먼저 잡는다 — advisory 대기 중 X 행 락이 비어 있다")
    void deleting_category_takes_member_lock_before_category() throws Exception {
        long x = createCustomCategory(MEMBER_A, "반려견");
        long august = insertBudget(MEMBER_A, "DEFAULT", AUGUST);
        insertAllocation(august, x, 300000);
        insertAllocation(august, SYSTEM_CATEGORY_ID, 100000);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (PreparedStatement lock =
                    holder.prepareStatement("select 1 from pg_advisory_xact_lock(hashtext('budget:' || ?))")) {
                lock.setString(1, MEMBER_A.toString());
                lock.executeQuery().close();
            }
            CompletableFuture<Void> deleteX =
                    CompletableFuture.runAsync(() -> catalogService.deleteCustomCategory(MEMBER_A, x), executor);

            assertThat(awaitWaiter("not granted and locktype = 'advisory'")).isNotNull();
            // 삭제가 X 를 먼저 UPDATE 했다면 이 nowait 는 55P03 으로 실패한다.
            try (Connection probe = dataSource.getConnection()) {
                probe.setAutoCommit(false);
                try (PreparedStatement rowLock =
                        probe.prepareStatement("select 1 from category where id = ? for update nowait")) {
                    rowLock.setLong(1, x);
                    rowLock.executeQuery().close();
                }
                probe.rollback();
            }

            holder.rollback();
            deleteX.get(30, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertThat(isActive(x)).isFalse();
        assertThat(allocatedCategories(budgetId(MEMBER_A, "DEFAULT", SEPTEMBER)))
                .containsExactly(SYSTEM_CATEGORY_ID);
    }

    @Test
    @DisplayName("purge 는 회원 예산 advisory lock 을 기다렸다가 락이 풀리면 예산을 모두 지운다")
    void purge_waits_for_member_budget_lock() throws Exception {
        long august = insertBudget(MEMBER_A, "DEFAULT", AUGUST);
        insertAllocation(august, SYSTEM_CATEGORY_ID, 100000);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (PreparedStatement lock =
                    holder.prepareStatement("select 1 from pg_advisory_xact_lock(hashtext('budget:' || ?))")) {
                lock.setString(1, MEMBER_A.toString());
                lock.executeQuery().close();
            }
            CompletableFuture<Void> purge =
                    CompletableFuture.runAsync(() -> ledgerPurgeService.purge(MEMBER_A), executor);

            assertThat(awaitWaiter("not granted and locktype = 'advisory'")).isNotNull();
            assertThat(budgetMonths(MEMBER_A, "DEFAULT")).containsExactly("2026-08-01");

            holder.rollback();
            purge.get(30, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertThat(jdbcTemplate.queryForObject("select count(*) from budget where member_id = ?", Long.class, MEMBER_A))
                .isZero();
    }

    @Test
    @DisplayName("지난 달 몫에만 남은 비활성 X 가 유예를 지나도 새 커스텀 카테고리 생성은 성공하고 X 와 몫은 남는다")
    void create_succeeds_and_keeps_inactive_category_referenced_by_past_allocation() throws Exception {
        long x = createCustomCategory(MEMBER_A, "반려견");
        long august = insertBudget(MEMBER_A, "MONTH", AUGUST);
        insertAllocation(august, x, 300000);
        deleteCustomCategory(MEMBER_A, x).andExpect(status().isOk());
        // 유예(24h) 기산점을 고정 시계보다 한참 앞으로 돌린다.
        jdbcTemplate.update("update category set updated_at = ? where id = ?", LocalDateTime.of(2026, 1, 1, 0, 0), x);

        createCustomCategory(MEMBER_A, "새 카테고리");

        assertThat(jdbcTemplate.queryForObject("select count(*) from category where id = ?", Long.class, x))
                .isEqualTo(1L);
        assertThat(allocatedCategories(august)).containsExactly(x);
        assertThat(categoryIds(read(MEMBER_A, AUGUST))).containsExactly(x);
    }

    @Test
    @DisplayName("B 가 자기 카테고리를 삭제해도, A 의 카테고리를 삭제하려 해도 A 의 몫은 그대로다 (IDOR)")
    void other_member_category_delete_keeps_member_a_allocations() throws Exception {
        long xA = createCustomCategory(MEMBER_A, "A 반려견");
        long xB = createCustomCategory(MEMBER_B, "B 반려견");
        long augustA = insertBudget(MEMBER_A, "DEFAULT", AUGUST);
        insertAllocation(augustA, xA, 300000);
        save(MEMBER_A, SEPTEMBER, categoryBudget("THIS_MONTH", xA)).andExpect(status().isOk());
        save(MEMBER_B, SEPTEMBER, categoryBudget("THIS_MONTH", xB)).andExpect(status().isOk());
        List<Map<String, Object>> memberABefore = allocationSnapshot(MEMBER_A);

        deleteCustomCategory(MEMBER_B, xB).andExpect(status().isOk());
        deleteCustomCategory(MEMBER_B, xA).andExpect(status().isNotFound());

        assertThat(allocationSnapshot(MEMBER_A)).isEqualTo(memberABefore);
        assertThat(budgetMonths(MEMBER_A, "DEFAULT")).containsExactly("2026-08-01");
        assertThat(isActive(xA)).isTrue();
        assertThat(allocationSnapshot(MEMBER_B)).isEmpty();
    }

    /** 조건에 맞는 락 대기자가 생길 때까지 폴링해 그 pid 를 돌려준다 — 시간이 아닌 pg_locks 로 판정한다. 없으면 null. */
    private Integer awaitWaiter(String condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            List<Integer> pids = jdbcTemplate.queryForList(
                    "select pid from pg_locks where " + condition + " limit 1", Integer.class);
            if (!pids.isEmpty()) {
                return pids.get(0);
            }
            Thread.sleep(20);
        }
        return null;
    }

    private ResultActions save(UUID memberId, YearMonth month, String body) throws Exception {
        return mockMvc.perform(put("/api/v1/budgets/EXPENSE")
                .with(memberJwt(memberId))
                .param("year", String.valueOf(month.getYear()))
                .param("month", String.valueOf(month.getMonthValue()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    /** 그 달의 지출 축. */
    private JsonNode read(UUID memberId, YearMonth month) throws Exception {
        return read(memberId, "expense", month);
    }

    private JsonNode read(UUID memberId, String axis, YearMonth month) throws Exception {
        String body = mockMvc.perform(get("/api/v1/budgets")
                        .with(memberJwt(memberId))
                        .param("year", String.valueOf(month.getYear()))
                        .param("month", String.valueOf(month.getMonthValue())))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(body).path("data").path(axis);
    }

    private static List<Long> categoryIds(JsonNode axis) {
        List<Long> ids = new ArrayList<>();
        axis.path("categories")
                .forEach(line -> ids.add(line.path("category").path("id").asLong()));
        return ids;
    }

    private long createCustomCategory(UUID memberId, String name) throws Exception {
        return createCustomCategory(memberId, "EXPENSE", name);
    }

    private long createCustomCategory(UUID memberId, String transactionType, String name) throws Exception {
        String body = mockMvc.perform(post("/api/v1/categories/custom")
                        .with(memberJwt(memberId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("transactionType", transactionType, "name", name))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(body).path("data").path("id").asLong();
    }

    private ResultActions deleteCustomCategory(UUID memberId, long categoryId) throws Exception {
        return mockMvc.perform(
                delete("/api/v1/categories/custom/{id}", categoryId).with(memberJwt(memberId)));
    }

    private static String categoryBudget(String applyTo, long categoryId) {
        return """
                {"applyTo":"%s","amounts":{"currency":"KRW","totalAmount":1000000,
                 "categoryAmounts":[{"categoryId":%d,"amount":300000}]}}"""
                .formatted(applyTo, categoryId);
    }

    // 지난 달은 API 로 쓸 수 없어 행을 직접 넣는다.
    private long insertBudget(UUID memberId, String kind, YearMonth month) {
        return insertBudget(memberId, "EXPENSE", kind, month);
    }

    private long insertBudget(UUID memberId, String axis, String kind, YearMonth month) {
        return jdbcTemplate.queryForObject(
                """
                insert into budget (member_id, axis, kind, month, currency_code, total_amount, created_at, updated_at)
                values (?, ?, ?, ?, 'KRW', 1000000, now(), now()) returning id
                """,
                Long.class,
                memberId,
                axis,
                kind,
                month.atDay(1));
    }

    private long budgetId(UUID memberId, String kind, YearMonth month) {
        return jdbcTemplate.queryForObject(
                "select id from budget where member_id = ? and kind = ? and month = ?",
                Long.class,
                memberId,
                kind,
                month.atDay(1));
    }

    private void insertAllocation(long budgetId, long categoryId, long amount) {
        jdbcTemplate.update(
                "insert into budget_allocation (budget_id, category_id, amount) values (?, ?, ?)",
                budgetId,
                categoryId,
                amount);
    }

    private List<Long> allocatedCategories(long budgetId) {
        return jdbcTemplate.queryForList(
                "select category_id from budget_allocation where budget_id = ? and category_id is not null",
                Long.class,
                budgetId);
    }

    private List<String> budgetMonths(UUID memberId, String kind) {
        return jdbcTemplate.queryForList(
                "select cast(month as text) from budget where member_id = ? and kind = ? order by month",
                String.class,
                memberId,
                kind);
    }

    private List<Map<String, Object>> allocationSnapshot(UUID memberId) {
        return jdbcTemplate.queryForList(
                """
                select a.id, a.budget_id, a.payment_group, a.category_id, a.amount
                from budget_allocation a join budget b on b.id = a.budget_id
                where b.member_id = ? order by a.id
                """,
                memberId);
    }

    private boolean isActive(long categoryId) {
        return Boolean.TRUE.equals(
                jdbcTemplate.queryForObject("select is_active from category where id = ?", Boolean.class, categoryId));
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

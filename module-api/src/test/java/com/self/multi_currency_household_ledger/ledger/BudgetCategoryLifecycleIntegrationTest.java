package com.self.multi_currency_household_ledger.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.self.multi_currency_household_ledger.AuthUserFixture;
import com.self.multi_currency_household_ledger.ledger.domain.CategoryRepository;
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
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 카테고리 삭제·정리와 예산 몫 — 서버 Clock 은 2026-09-15(KST) 로 고정한다. 이번 달 = 9월. */
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

    // 삭제 카테고리 쿼리 호출 수를 센다 — 동작은 실제 저장소에 위임한다.
    @MockitoSpyBean
    private CategoryRepository categoryRepository;

    @BeforeEach
    void setUp() {
        new AuthUserFixture(jdbcTemplate).reset(MEMBER_A, MEMBER_B);
    }

    @Test
    @DisplayName("지난 달·이번 달·다음 달에 C 몫이 있을 때 C 를 지우면 세 달 모두 C 줄이 deleted:true·같은 금액·이름 그대로 남는다")
    void deleting_category_keeps_its_shares_in_past_current_and_next_month() throws Exception {
        long c = createCustomCategory(MEMBER_A, "반려견");
        for (YearMonth month : List.of(AUGUST, SEPTEMBER, OCTOBER)) {
            save(MEMBER_A, month, categoryBudget(c)).andExpect(status().isOk());
        }

        deleteCustomCategory(MEMBER_A, c).andExpect(status().isOk());

        for (YearMonth month : List.of(AUGUST, SEPTEMBER, OCTOBER)) {
            JsonNode lines = read(MEMBER_A, month).path("categories");
            assertThat(lines).hasSize(1);
            JsonNode line = lines.get(0);
            assertThat(line.path("category").path("id").asLong()).isEqualTo(c);
            assertThat(line.path("category").path("displayNameKo").asString()).isEqualTo("반려견");
            assertThat(line.path("deleted").asBoolean()).isTrue();
            assertThat(line.path("budgetAmount").decimalValue()).isEqualByComparingTo("300000");
        }
        assertThat(isActive(c)).isFalse();
    }

    @Test
    @DisplayName("그 달에 이미 있던 삭제된 C 몫은 저장에서 받는다 — 금액을 바꿔 PUT 하면 200 이고 새 금액이다")
    void saving_month_keeps_existing_deleted_category_share_and_updates_amount() throws Exception {
        long c = createCustomCategory(MEMBER_A, "반려견");
        save(MEMBER_A, SEPTEMBER, categoryBudget(c)).andExpect(status().isOk());
        deleteCustomCategory(MEMBER_A, c).andExpect(status().isOk());

        JsonNode line = data(save(
                                MEMBER_A,
                                SEPTEMBER,
                                """
                                {"currency":"KRW","totalAmount":1000000,
                                 "categoryAmounts":[{"categoryId":%d,"amount":400000}]}"""
                                        .formatted(c))
                        .andExpect(status().isOk()))
                .path("categories")
                .get(0);

        assertThat(line.path("category").path("id").asLong()).isEqualTo(c);
        assertThat(line.path("deleted").asBoolean()).isTrue();
        assertThat(line.path("budgetAmount").decimalValue()).isEqualByComparingTo("400000");
        assertThat(allocatedCategories(budgetId(MEMBER_A, SEPTEMBER))).containsExactly(c);
    }

    @Test
    @DisplayName("삭제된 C 몫을 뺀 PUT 이면 C 몫이 사라진다")
    void saving_month_drops_deleted_category_share_when_omitted() throws Exception {
        long c = createCustomCategory(MEMBER_A, "반려견");
        save(MEMBER_A, SEPTEMBER, categoryBudget(c, SYSTEM_CATEGORY_ID)).andExpect(status().isOk());
        deleteCustomCategory(MEMBER_A, c).andExpect(status().isOk());

        save(MEMBER_A, SEPTEMBER, categoryBudget(SYSTEM_CATEGORY_ID)).andExpect(status().isOk());

        assertThat(categoryIds(read(MEMBER_A, SEPTEMBER))).containsExactly(SYSTEM_CATEGORY_ID);
        assertThat(allocatedCategories(budgetId(MEMBER_A, SEPTEMBER))).containsExactly(SYSTEM_CATEGORY_ID);
    }

    @Test
    @DisplayName("그 달에 없던 삭제된 C 를 넣는 PUT 은 다른 달에 C 몫이 있어도 404 CATEGORY_NOT_FOUND 이고 그 달 행은 그대로다")
    void saving_month_rejects_deleted_category_absent_from_that_month() throws Exception {
        long c = createCustomCategory(MEMBER_A, "반려견");
        save(MEMBER_A, AUGUST, categoryBudget(c)).andExpect(status().isOk());
        save(MEMBER_A, SEPTEMBER, categoryBudget(SYSTEM_CATEGORY_ID)).andExpect(status().isOk());
        deleteCustomCategory(MEMBER_A, c).andExpect(status().isOk());
        List<Map<String, Object>> before = allocationSnapshot(MEMBER_A);

        save(MEMBER_A, SEPTEMBER, categoryBudget(c, SYSTEM_CATEGORY_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CATEGORY_NOT_FOUND"));

        assertThat(allocationSnapshot(MEMBER_A)).isEqualTo(before);
        assertThat(categoryIds(read(MEMBER_A, SEPTEMBER))).containsExactly(SYSTEM_CATEGORY_ID);
    }

    @Test
    @DisplayName("삭제된 C 의 거래는 C 몫이 없는 달엔 그 외 카테고리로, C 몫이 있는 달엔 C 줄(deleted:true)로 간다")
    void deleted_category_spending_without_share_goes_to_other_categories() throws Exception {
        long c = createCustomCategory(MEMBER_A, "반려견");
        createEntry(MEMBER_A, "40000", c, "2026-08-10");
        createEntry(MEMBER_A, "30000", c, "2026-09-10");
        createEntry(MEMBER_A, "5000", SYSTEM_CATEGORY_ID, "2026-09-11");
        save(MEMBER_A, AUGUST, categoryBudget(c)).andExpect(status().isOk());
        save(MEMBER_A, SEPTEMBER, categoryBudget(SYSTEM_CATEGORY_ID)).andExpect(status().isOk());
        deleteCustomCategory(MEMBER_A, c).andExpect(status().isOk());

        JsonNode september = read(MEMBER_A, SEPTEMBER);
        assertThat(categoryIds(september)).containsExactly(SYSTEM_CATEGORY_ID);
        assertThat(september.path("categories").get(0).path("actualAmount").decimalValue())
                .isEqualByComparingTo("5000");
        assertThat(september.path("otherCategories").path("budgetAmount").decimalValue())
                .isEqualByComparingTo("700000");
        assertThat(september.path("otherCategories").path("actualAmount").decimalValue())
                .isEqualByComparingTo("30000");

        JsonNode august = read(MEMBER_A, AUGUST);
        JsonNode line = august.path("categories").get(0);
        assertThat(categoryIds(august)).containsExactly(c);
        assertThat(line.path("deleted").asBoolean()).isTrue();
        assertThat(line.path("actualAmount").decimalValue()).isEqualByComparingTo("40000");
        assertThat(august.path("otherCategories").path("actualAmount").decimalValue())
                .isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("카테고리 삭제는 회원 예산 advisory lock 을 잡지 않는다 — 다른 트랜잭션이 락을 쥔 채로도 기다리지 않고 끝난다")
    void deleting_category_does_not_take_member_budget_lock() throws Exception {
        long c = createCustomCategory(MEMBER_A, "반려견");
        save(MEMBER_A, SEPTEMBER, categoryBudget(c)).andExpect(status().isOk());

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (PreparedStatement lock =
                    holder.prepareStatement("select 1 from pg_advisory_xact_lock(hashtext('budget:' || ?))")) {
                lock.setString(1, MEMBER_A.toString());
                lock.executeQuery().close();
            }
            CompletableFuture<Void> deleteC =
                    CompletableFuture.runAsync(() -> catalogService.deleteCustomCategory(MEMBER_A, c), executor);

            // 락을 쥔 채로 끝나야 한다 — 삭제가 회원 락을 잡으면 여기서 TimeoutException 이다.
            deleteC.get(10, TimeUnit.SECONDS);
            holder.rollback();
        } finally {
            executor.shutdownNow();
        }

        assertThat(isActive(c)).isFalse();
        assertThat(allocatedCategories(budgetId(MEMBER_A, SEPTEMBER))).containsExactly(c);
    }

    @Test
    @DisplayName("같은 달 몫이 있는 두 카테고리를 동시에 삭제해도 둘 다 성공하고 몫은 deleted:true 로 남는다")
    void concurrent_deletes_of_two_categories_both_succeed() throws Exception {
        long x = createCustomCategory(MEMBER_A, "반려견");
        long y = createCustomCategory(MEMBER_A, "어학원");
        save(MEMBER_A, SEPTEMBER, categoryBudget(x, y, SYSTEM_CATEGORY_ID)).andExpect(status().isOk());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CompletableFuture<Void> deleteX =
                    CompletableFuture.runAsync(() -> catalogService.deleteCustomCategory(MEMBER_A, x), executor);
            CompletableFuture<Void> deleteY =
                    CompletableFuture.runAsync(() -> catalogService.deleteCustomCategory(MEMBER_A, y), executor);
            CompletableFuture.allOf(deleteX, deleteY).get(30, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertThat(isActive(x)).isFalse();
        assertThat(isActive(y)).isFalse();
        assertThat(allocatedCategories(budgetId(MEMBER_A, SEPTEMBER)))
                .containsExactlyInAnyOrder(x, y, SYSTEM_CATEGORY_ID);
        JsonNode lines = read(MEMBER_A, SEPTEMBER).path("categories");
        List<Long> deleted = new ArrayList<>();
        lines.forEach(line -> {
            if (line.path("deleted").asBoolean()) {
                deleted.add(line.path("category").path("id").asLong());
            }
        });
        assertThat(deleted).containsExactlyInAnyOrder(x, y);
        assertThat(categoryIds(read(MEMBER_A, SEPTEMBER))).containsExactlyInAnyOrder(x, y, SYSTEM_CATEGORY_ID);
    }

    @Test
    @DisplayName("purge 는 회원 예산 advisory lock 을 기다렸다가 락이 풀리면 예산을 모두 지운다")
    void purge_waits_for_member_budget_lock() throws Exception {
        long august = insertBudget(MEMBER_A, AUGUST);
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
            assertThat(budgetMonths(MEMBER_A)).containsExactly("2026-08-01");

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
        long august = insertBudget(MEMBER_A, AUGUST);
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
        long augustA = insertBudget(MEMBER_A, AUGUST);
        insertAllocation(augustA, xA, 300000);
        save(MEMBER_A, SEPTEMBER, categoryBudget(xA)).andExpect(status().isOk());
        save(MEMBER_B, SEPTEMBER, categoryBudget(xB)).andExpect(status().isOk());
        List<Map<String, Object>> memberABefore = allocationSnapshot(MEMBER_A);

        deleteCustomCategory(MEMBER_B, xB).andExpect(status().isOk());
        deleteCustomCategory(MEMBER_B, xA).andExpect(status().isNotFound());

        assertThat(allocationSnapshot(MEMBER_A)).isEqualTo(memberABefore);
        assertThat(budgetMonths(MEMBER_A)).containsExactly("2026-08-01", "2026-09-01");
        assertThat(isActive(xA)).isTrue();
        assertThat(isActive(xB)).isFalse();
    }

    @Test
    @DisplayName("그 달 지출이 있는 삭제된 C 는 줄을 빼고 저장한 뒤에도 목록에 남고, 다시 넣는 PUT 은 200 이며 C 가 줄 끝에 온다")
    void deleted_category_spent_in_month_can_be_readded_after_removal() throws Exception {
        long c = createCustomCategory(MEMBER_A, "반려견");
        createEntry(MEMBER_A, "30000", c, "2026-09-10");
        save(MEMBER_A, SEPTEMBER, categoryBudget(c, SYSTEM_CATEGORY_ID)).andExpect(status().isOk());
        deleteCustomCategory(MEMBER_A, c).andExpect(status().isOk());

        JsonNode editing = read(MEMBER_A, SEPTEMBER);
        assertThat(categoryIds(editing)).containsExactly(c, SYSTEM_CATEGORY_ID);
        assertThat(editing.path("categories").get(0).path("deleted").asBoolean())
                .isTrue();
        assertThat(deletedWithSpendingIds(editing)).containsExactly(c);

        save(MEMBER_A, SEPTEMBER, categoryBudget(SYSTEM_CATEGORY_ID)).andExpect(status().isOk());
        JsonNode removed = read(MEMBER_A, SEPTEMBER);
        assertThat(categoryIds(removed)).containsExactly(SYSTEM_CATEGORY_ID);
        assertThat(deletedWithSpendingIds(removed)).containsExactly(c);
        assertThat(removed.path("otherCategories").path("actualAmount").decimalValue())
                .isEqualByComparingTo("30000");

        JsonNode readded = data(
                save(MEMBER_A, SEPTEMBER, categoryBudget(SYSTEM_CATEGORY_ID, c)).andExpect(status().isOk()));
        assertThat(categoryIds(readded)).containsExactly(SYSTEM_CATEGORY_ID, c);
        assertThat(readded.path("categories").get(1).path("deleted").asBoolean())
                .isTrue();
    }

    @Test
    @DisplayName("예산이 없는 달에도 그 달 지출이 있는 삭제된 C 가 목록에 있고, C 를 넣는 PUT 은 200 이다")
    void not_set_month_lists_deleted_category_spent_in_month_and_accepts_it() throws Exception {
        long c = createCustomCategory(MEMBER_A, "반려견");
        createEntry(MEMBER_A, "30000", c, "2026-10-05");
        deleteCustomCategory(MEMBER_A, c).andExpect(status().isOk());

        JsonNode notSet = read(MEMBER_A, OCTOBER);
        assertThat(notSet.path("status").asString()).isEqualTo("NOT_SET");
        assertThat(deletedWithSpendingIds(notSet)).containsExactly(c);

        JsonNode saved = data(save(MEMBER_A, OCTOBER, categoryBudget(c)).andExpect(status().isOk()));
        assertThat(categoryIds(saved)).containsExactly(c);
        assertThat(saved.path("categories").get(0).path("deleted").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("삭제된 C 의 지출이 8/31·10/1 뿐이면 9월 목록에 없고 9월에 C 를 넣는 PUT 은 404 이며 9월 행은 그대로다")
    void deleted_category_spent_only_in_other_months_is_not_listed_or_accepted() throws Exception {
        long c = createCustomCategory(MEMBER_A, "반려견");
        createEntry(MEMBER_A, "40000", c, "2026-08-31");
        createEntry(MEMBER_A, "30000", c, "2026-10-01");
        save(MEMBER_A, SEPTEMBER, categoryBudget(SYSTEM_CATEGORY_ID)).andExpect(status().isOk());
        deleteCustomCategory(MEMBER_A, c).andExpect(status().isOk());
        List<Map<String, Object>> before = allocationSnapshot(MEMBER_A);

        assertThat(deletedWithSpendingIds(read(MEMBER_A, SEPTEMBER))).isEmpty();
        assertThat(deletedWithSpendingIds(read(MEMBER_A, AUGUST))).containsExactly(c);
        assertThat(deletedWithSpendingIds(read(MEMBER_A, OCTOBER))).containsExactly(c);

        save(MEMBER_A, SEPTEMBER, categoryBudget(c))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CATEGORY_NOT_FOUND"));

        assertThat(allocationSnapshot(MEMBER_A)).isEqualTo(before);
        assertThat(categoryIds(read(MEMBER_A, SEPTEMBER))).containsExactly(SYSTEM_CATEGORY_ID);
    }

    @Test
    @DisplayName("USD 예산에서 환율이 없어 환산되지 못한 C 의 지출이 있어도 C 는 목록에 있고 C 를 넣는 PUT 은 200 이다")
    void deleted_category_with_unconverted_spending_is_still_listed() throws Exception {
        jdbcTemplate.update("delete from exchange_rate where currency_code = 'USD'");
        long c = createCustomCategory(MEMBER_A, "반려견");
        createEntry(MEMBER_A, "30000", c, "2026-09-10");
        save(MEMBER_A, SEPTEMBER, """
                        {"currency":"USD","totalAmount":1000}""")
                .andExpect(status().isOk());
        deleteCustomCategory(MEMBER_A, c).andExpect(status().isOk());

        JsonNode september = read(MEMBER_A, SEPTEMBER);
        assertThat(september.path("missingRateCount").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(deletedWithSpendingIds(september)).containsExactly(c);

        JsonNode saved = data(save(
                        MEMBER_A,
                        SEPTEMBER,
                        """
                        {"currency":"USD","totalAmount":1000,
                         "categoryAmounts":[{"categoryId":%d,"amount":300}]}"""
                                .formatted(c))
                .andExpect(status().isOk()));
        assertThat(saved.path("currency").asString()).isEqualTo("USD");
        assertThat(categoryIds(saved)).containsExactly(c);
    }

    @Test
    @DisplayName("A 의 삭제된 C 는 B 의 목록에 없고 B 가 C 를 넣는 PUT 은 404 이며 A 의 몫은 그대로다 (IDOR)")
    void other_member_cannot_list_or_add_member_a_deleted_category() throws Exception {
        long c = createCustomCategory(MEMBER_A, "반려견");
        createEntry(MEMBER_A, "30000", c, "2026-09-10");
        save(MEMBER_A, SEPTEMBER, categoryBudget(c, SYSTEM_CATEGORY_ID)).andExpect(status().isOk());
        deleteCustomCategory(MEMBER_A, c).andExpect(status().isOk());
        List<Map<String, Object>> memberABefore = allocationSnapshot(MEMBER_A);

        assertThat(deletedWithSpendingIds(read(MEMBER_B, SEPTEMBER))).isEmpty();
        save(MEMBER_B, SEPTEMBER, categoryBudget(c))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CATEGORY_NOT_FOUND"));

        assertThat(allocationSnapshot(MEMBER_A)).isEqualTo(memberABefore);
        assertThat(budgetMonths(MEMBER_B)).isEmpty();
        assertThat(deletedWithSpendingIds(read(MEMBER_A, SEPTEMBER))).containsExactly(c);
    }

    @Test
    @DisplayName("사용 가능한 카테고리만 넣는 PUT 은 저장 검증에서 삭제 카테고리 쿼리를 부르지 않고, 삭제된 C 를 새로 넣을 때만 부른다")
    void saving_only_usable_categories_does_not_query_deleted_categories() throws Exception {
        long c = createCustomCategory(MEMBER_A, "반려견");
        createEntry(MEMBER_A, "30000", c, "2026-09-10");
        deleteCustomCategory(MEMBER_A, c).andExpect(status().isOk());

        clearInvocations(categoryRepository);
        save(MEMBER_A, SEPTEMBER, categoryBudget(SYSTEM_CATEGORY_ID)).andExpect(status().isOk());
        // PUT 응답을 만드는 읽기의 1회뿐이다.
        verify(categoryRepository, times(1)).findDeletedWithExpenses(any(), any(), any());

        clearInvocations(categoryRepository);
        save(MEMBER_A, SEPTEMBER, categoryBudget(SYSTEM_CATEGORY_ID, c)).andExpect(status().isOk());
        // 저장 검증 1회 + 읽기 1회.
        verify(categoryRepository, times(2)).findDeletedWithExpenses(any(), any(), any());
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
        return mockMvc.perform(put("/api/v1/budgets")
                .with(memberJwt(memberId))
                .param("year", String.valueOf(month.getYear()))
                .param("month", String.valueOf(month.getMonthValue()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
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

    private static List<Long> categoryIds(JsonNode budget) {
        List<Long> ids = new ArrayList<>();
        budget.path("categories")
                .forEach(line -> ids.add(line.path("category").path("id").asLong()));
        return ids;
    }

    /** deletedCategoriesWithSpending 의 id 들. 필드가 없거나 null 이면 실패한다 — 빈 경우도 배열이어야 한다. */
    private static List<Long> deletedWithSpendingIds(JsonNode budget) {
        JsonNode categories = budget.path("deletedCategoriesWithSpending");
        assertThat(categories.isArray()).isTrue();
        List<Long> ids = new ArrayList<>();
        categories.forEach(category -> ids.add(category.path("id").asLong()));
        return ids;
    }

    private long createCustomCategory(UUID memberId, String name) throws Exception {
        String body = mockMvc.perform(post("/api/v1/categories/custom")
                        .with(memberJwt(memberId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("transactionType", "EXPENSE", "name", name))))
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

    /** 자산 1 에 KRW 지출 한 건. */
    private void createEntry(UUID memberId, String amount, long categoryId, String date) throws Exception {
        mockMvc.perform(post("/api/v1/ledgers")
                        .with(memberJwt(memberId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {"amount":%s,"currencyCode":"KRW","categoryId":%d,"assetId":1,"transactionDate":"%s"}"""
                                        .formatted(amount, categoryId, date)))
                .andExpect(status().isOk());
    }

    /** 카테고리마다 300,000원 몫을 둔 KRW 1,000,000원 세트. */
    private static String categoryBudget(long... categoryIds) {
        String items = Arrays.stream(categoryIds)
                .mapToObj(id -> "{\"categoryId\":%d,\"amount\":300000}".formatted(id))
                .collect(Collectors.joining(","));
        return """
                {"currency":"KRW","totalAmount":1000000,"categoryAmounts":[%s]}""".formatted(items);
    }

    private long insertBudget(UUID memberId, YearMonth month) {
        return jdbcTemplate.queryForObject(
                """
                insert into budget (member_id, month, currency_code, total_amount, created_at, updated_at)
                values (?, ?, 'KRW', 1000000, now(), now()) returning id
                """,
                Long.class,
                memberId,
                month.atDay(1));
    }

    private long budgetId(UUID memberId, YearMonth month) {
        return jdbcTemplate.queryForObject(
                "select id from budget where member_id = ? and month = ?", Long.class, memberId, month.atDay(1));
    }

    private void insertAllocation(long budgetId, long categoryId, long amount) {
        jdbcTemplate.update(
                "insert into budget_category_allocation (budget_id, category_id, amount) values (?, ?, ?)",
                budgetId,
                categoryId,
                amount);
    }

    private List<Long> allocatedCategories(long budgetId) {
        return jdbcTemplate.queryForList(
                "select category_id from budget_category_allocation where budget_id = ?", Long.class, budgetId);
    }

    private List<String> budgetMonths(UUID memberId) {
        return jdbcTemplate.queryForList(
                "select cast(month as text) from budget where member_id = ? order by month", String.class, memberId);
    }

    private List<Map<String, Object>> allocationSnapshot(UUID memberId) {
        return jdbcTemplate.queryForList(
                """
                select b.id, b.credit_card_amount, b.cash_and_debit_amount, b.account_and_other_amount,
                       a.category_id, a.amount
                from budget b left join budget_category_allocation a on a.budget_id = b.id
                where b.member_id = ? order by b.id, a.category_id
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

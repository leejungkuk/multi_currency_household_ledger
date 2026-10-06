package com.self.multi_currency_household_ledger.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace.NONE;

import com.self.multi_currency_household_ledger.common.exception.BusinessException;
import com.self.multi_currency_household_ledger.exchange.domain.CurrencyCode;
import com.self.multi_currency_household_ledger.exchange.service.ExchangeRateService;
import com.self.multi_currency_household_ledger.ledger.AuthUserFixture;
import com.self.multi_currency_household_ledger.ledger.TestJpaConfig;
import com.self.multi_currency_household_ledger.ledger.TestLedgerApplication;
import com.self.multi_currency_household_ledger.ledger.domain.Budget;
import com.self.multi_currency_household_ledger.ledger.domain.Budget.CategoryAmount;
import com.self.multi_currency_household_ledger.ledger.domain.Budget.GroupAmount;
import com.self.multi_currency_household_ledger.ledger.domain.BudgetRepository;
import com.self.multi_currency_household_ledger.ledger.domain.Category;
import com.self.multi_currency_household_ledger.ledger.domain.CategoryRepository;
import com.self.multi_currency_household_ledger.ledger.domain.PaymentGroup;
import com.self.multi_currency_household_ledger.ledger.domain.TransactionType;
import com.self.multi_currency_household_ledger.ledger.dto.GuestBudgetImportResponse;
import com.self.multi_currency_household_ledger.ledger.exception.BudgetErrorCode;
import com.self.multi_currency_household_ledger.ledger.exception.LedgerErrorCode;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@Import({
    TestLedgerApplication.class,
    TestJpaConfig.class,
    BudgetService.class,
    BudgetGuestImportServiceIntegrationTest.ClockConfig.class
})
@MockitoBean(types = ExchangeRateService.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class BudgetGuestImportServiceIntegrationTest {

    private static final UUID GUEST = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID MEMBER = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final YearMonth AUGUST = YearMonth.of(2026, 8);
    private static final YearMonth SEPTEMBER = YearMonth.of(2026, 9);
    private static final YearMonth OCTOBER = YearMonth.of(2026, 10);

    @Autowired
    private BudgetService budgetService;

    @Autowired
    private BudgetRepository budgetRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long gU1;
    private long gU2;
    private long mU1;
    private long bU1;
    private long d1;
    private long d2;

    @BeforeEach
    void setUp() {
        new AuthUserFixture(jdbcTemplate).reset(GUEST, MEMBER, OTHER);
        jdbcTemplate.update("delete from budget");
        gU1 = custom(GUEST, TransactionType.EXPENSE);
        gU2 = custom(GUEST, TransactionType.EXPENSE);
        mU1 = custom(MEMBER, TransactionType.EXPENSE);
        bU1 = custom(OTHER, TransactionType.EXPENSE);
        List<Long> defaults = jdbcTemplate.queryForList(
                "select id from category where owner_member_id is null and transaction_type = 'EXPENSE' order by id limit 2",
                Long.class);
        d1 = defaults.get(0);
        d2 = defaults.get(1);
    }

    @Test
    @DisplayName("비회원의 모든 달을 대응된 줄·같은 순서로 옮기고 비회원 행은 그대로다")
    void imports_every_guest_month_with_mapped_lines_in_order() {
        save(
                GUEST,
                AUGUST,
                CurrencyCode.KRW,
                "1000",
                List.of(new GroupAmount(PaymentGroup.CASH_AND_DEBIT, new BigDecimal("400"))),
                gU1,
                d1);
        save(GUEST, OCTOBER, CurrencyCode.USD, "50", List.of(), d2);

        GuestBudgetImportResponse response = budgetService.importFromGuest(MEMBER, GUEST, Map.of(gU1, mU1));

        assertThat(response).isEqualTo(new GuestBudgetImportResponse(2, 0));
        assertThat(snapshot(MEMBER, AUGUST))
                .isEqualTo("KRW|1000.00|[CASH_AND_DEBIT=400.00]|[" + mU1 + ":100.00:0, " + d1 + ":100.00:1]");
        assertThat(snapshot(MEMBER, OCTOBER)).isEqualTo("USD|50.00|[]|[" + d2 + ":5.00:0]");
        assertThat(snapshot(GUEST, AUGUST))
                .isEqualTo("KRW|1000.00|[CASH_AND_DEBIT=400.00]|[" + gU1 + ":100.00:0, " + d1 + ":100.00:1]");
        assertThat(snapshot(GUEST, OCTOBER)).isEqualTo("USD|50.00|[]|[" + d2 + ":5.00:0]");
        assertThat(rowCount(GUEST)).isEqualTo(2);
    }

    @Test
    @DisplayName("회원에게 이미 있는 달은 건너뛰고 그 행을 건드리지 않는다")
    void skips_month_member_already_has_without_touching_it() {
        save(GUEST, AUGUST, CurrencyCode.KRW, "1000", List.of(), d1);
        save(GUEST, SEPTEMBER, CurrencyCode.KRW, "2000", List.of(), d1);
        save(MEMBER, AUGUST, CurrencyCode.USD, "70", List.of(), d2, d1);
        String before = snapshot(MEMBER, AUGUST);
        Object updatedAt = updatedAt(MEMBER, AUGUST);

        GuestBudgetImportResponse response = budgetService.importFromGuest(MEMBER, GUEST, Map.of());

        assertThat(response).isEqualTo(new GuestBudgetImportResponse(1, 1));
        assertThat(snapshot(MEMBER, AUGUST)).isEqualTo(before);
        assertThat(updatedAt(MEMBER, AUGUST)).isEqualTo(updatedAt);
        assertThat(snapshot(MEMBER, SEPTEMBER)).startsWith("KRW|2000.00");
    }

    @Test
    @DisplayName("같은 인자로 다시 부르면 모든 달을 건너뛰고 아무것도 바뀌지 않는다")
    void repeated_import_skips_every_month_and_changes_nothing() {
        save(GUEST, AUGUST, CurrencyCode.KRW, "1000", List.of(), gU1, d1);
        save(GUEST, SEPTEMBER, CurrencyCode.KRW, "2000", List.of(), d2);
        Map<Long, Long> map = Map.of(gU1, mU1);
        assertThat(budgetService.importFromGuest(MEMBER, GUEST, map)).isEqualTo(new GuestBudgetImportResponse(2, 0));
        String august = snapshot(MEMBER, AUGUST);
        String september = snapshot(MEMBER, SEPTEMBER);
        Object updatedAt = updatedAt(MEMBER, AUGUST);

        assertThat(budgetService.importFromGuest(MEMBER, GUEST, map)).isEqualTo(new GuestBudgetImportResponse(0, 2));

        assertThat(rowCount(MEMBER)).isEqualTo(2);
        assertThat(snapshot(MEMBER, AUGUST)).isEqualTo(august);
        assertThat(snapshot(MEMBER, SEPTEMBER)).isEqualTo(september);
        assertThat(updatedAt(MEMBER, AUGUST)).isEqualTo(updatedAt);
    }

    @Test
    @DisplayName("대응 없는 비회원 카테고리 줄만 빠지고 전체 금액은 그대로다")
    void drops_line_without_member_counterpart_and_keeps_total() {
        save(GUEST, AUGUST, CurrencyCode.KRW, "1000", List.of(), gU1, gU2, d1);

        budgetService.importFromGuest(MEMBER, GUEST, Map.of(gU1, mU1));

        assertThat(snapshot(MEMBER, AUGUST)).isEqualTo("KRW|1000.00|[]|[" + mU1 + ":100.00:0, " + d1 + ":100.00:1]");
    }

    @Test
    @DisplayName("대응 대상이 삭제됐고 그 달 지출이 없어도 줄을 옮긴다")
    void moves_line_of_deleted_mapped_category_without_spending_in_month() {
        categoryRepository.findById(mU1).orElseThrow().deactivate();
        categoryRepository.flush();
        save(GUEST, AUGUST, CurrencyCode.KRW, "1000", List.of(), gU1);

        budgetService.importFromGuest(MEMBER, GUEST, Map.of(gU1, mU1));

        assertThat(snapshot(MEMBER, AUGUST)).isEqualTo("KRW|1000.00|[]|[" + mU1 + ":100.00:0]");
    }

    @Test
    @DisplayName("대응 대상이 회원 소유가 아니면(타 회원·비회원·기본·없는 id) 404 이고 아무것도 쓰지 않는다")
    void rejects_mapping_target_not_owned_by_member_and_writes_nothing() {
        save(GUEST, AUGUST, CurrencyCode.KRW, "1000", List.of(), gU1);
        save(GUEST, SEPTEMBER, CurrencyCode.KRW, "1000", List.of(), d1);
        save(OTHER, AUGUST, CurrencyCode.KRW, "1000", List.of(), bU1);

        for (long target : new long[] {bU1, gU1, d1, 987_654_321L}) {
            assertThatThrownBy(() -> budgetService.importFromGuest(MEMBER, GUEST, Map.of(gU1, target)))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", LedgerErrorCode.CATEGORY_NOT_FOUND.getCode());
        }

        assertThat(rowCount(MEMBER)).isZero();
        assertThat(rowCount(GUEST)).isEqualTo(2);
        assertThat(snapshot(OTHER, AUGUST)).isEqualTo("KRW|1000.00|[]|[" + bU1 + ":100.00:0]");
        assertThat(snapshot(GUEST, AUGUST)).isEqualTo("KRW|1000.00|[]|[" + gU1 + ":100.00:0]");
    }

    @Test
    @DisplayName("줄에 쓰인 대응 대상이 수입 카테고리면 400 이고 어느 달도 옮기지 않는다")
    void rejects_income_target_used_by_line_and_writes_no_month() {
        long mIncome = custom(MEMBER, TransactionType.INCOME);
        save(GUEST, AUGUST, CurrencyCode.KRW, "1000", List.of(), d1);
        save(GUEST, SEPTEMBER, CurrencyCode.KRW, "1000", List.of(), gU1);

        assertThatThrownBy(() -> budgetService.importFromGuest(MEMBER, GUEST, Map.of(gU1, mIncome)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", BudgetErrorCode.BUDGET_INVALID_ALLOCATION.getCode());

        assertThat(rowCount(MEMBER)).isZero();
        assertThat(rowCount(GUEST)).isEqualTo(2);
    }

    @Test
    @DisplayName("다른 회원의 예산은 옮기지 않는다")
    void does_not_import_other_members_budgets() {
        save(GUEST, AUGUST, CurrencyCode.KRW, "1000", List.of(), d1);
        save(OTHER, SEPTEMBER, CurrencyCode.KRW, "2000", List.of(), bU1);

        GuestBudgetImportResponse response = budgetService.importFromGuest(MEMBER, GUEST, Map.of());

        assertThat(response).isEqualTo(new GuestBudgetImportResponse(1, 0));
        assertThat(budgetRepository.findMonthsByMemberId(MEMBER)).containsExactly(AUGUST.atDay(1));
        assertThat(snapshot(OTHER, SEPTEMBER)).isEqualTo("KRW|2000.00|[]|[" + bU1 + ":200.00:0]");
    }

    @Test
    @DisplayName("비회원에게 예산이 없으면 0·0 이다")
    void returns_zero_counts_when_guest_has_no_budget() {
        assertThat(budgetService.importFromGuest(MEMBER, GUEST, Map.of(gU1, mU1)))
                .isEqualTo(new GuestBudgetImportResponse(0, 0));
        assertThat(rowCount(MEMBER)).isZero();
    }

    private long custom(UUID owner, TransactionType type) {
        return categoryRepository
                .saveAndFlush(Category.custom(owner, type, "c", null))
                .getId();
    }

    private void save(
            UUID memberId,
            YearMonth month,
            CurrencyCode currency,
            String total,
            List<GroupAmount> groups,
            long... categoryIds) {
        Budget budget = new Budget(memberId, month);
        BigDecimal share = new BigDecimal(total).divide(BigDecimal.TEN);
        budget.replaceAmounts(
                currency,
                new BigDecimal(total),
                groups,
                Arrays.stream(categoryIds)
                        .mapToObj(id -> new CategoryAmount(id, share))
                        .toList());
        budgetRepository.saveAndFlush(budget);
    }

    /** DB 에서 읽은 행: 통화|전체|결제수단 몫|카테고리 id:금액:줄 순서(줄 순서대로). */
    private String snapshot(UUID memberId, YearMonth month) {
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "select id, currency_code, total_amount, credit_card_amount, cash_and_debit_amount, account_and_other_amount"
                        + " from budget where member_id = ? and month = ?",
                memberId,
                month.atDay(1));
        StringBuilder groups = new StringBuilder("[");
        String[] names = {"CREDIT_CARD", "CASH_AND_DEBIT", "ACCOUNT_AND_OTHER"};
        String[] columns = {"credit_card_amount", "cash_and_debit_amount", "account_and_other_amount"};
        for (int i = 0; i < names.length; i++) {
            if (row.get(columns[i]) != null) {
                groups.append(groups.length() > 1 ? ", " : "")
                        .append(names[i])
                        .append('=')
                        .append(row.get(columns[i]));
            }
        }
        List<String> lines = jdbcTemplate.query(
                "select category_id, amount, sort_order from budget_category_allocation where budget_id = ?"
                        + " order by sort_order",
                (rs, n) -> rs.getLong("category_id") + ":" + rs.getBigDecimal("amount") + ":" + rs.getInt("sort_order"),
                row.get("id"));
        return row.get("currency_code") + "|" + row.get("total_amount") + "|" + groups + "]|" + lines;
    }

    private Object updatedAt(UUID memberId, YearMonth month) {
        return jdbcTemplate.queryForObject(
                "select updated_at from budget where member_id = ? and month = ?",
                Object.class,
                memberId,
                month.atDay(1));
    }

    private int rowCount(UUID memberId) {
        Integer count =
                jdbcTemplate.queryForObject("select count(*) from budget where member_id = ?", Integer.class, memberId);
        return count == null ? 0 : count;
    }

    @TestConfiguration
    static class ClockConfig {

        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-10-05T15:00:00Z"), ZoneId.of("Asia/Seoul"));
        }
    }
}

package com.self.multi_currency_household_ledger.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace.NONE;

import com.self.multi_currency_household_ledger.exchange.domain.CurrencyCode;
import com.self.multi_currency_household_ledger.ledger.AuthUserFixture;
import com.self.multi_currency_household_ledger.ledger.TestJpaConfig;
import com.self.multi_currency_household_ledger.ledger.TestLedgerApplication;
import com.self.multi_currency_household_ledger.ledger.domain.Budget.CategoryAmount;
import com.self.multi_currency_household_ledger.ledger.domain.Budget.GroupAmount;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@Import({TestLedgerApplication.class, TestJpaConfig.class})
class BudgetRepositoryTest {

    private static final UUID MEMBER_A = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID MEMBER_B = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final YearMonth SEPTEMBER = YearMonth.of(2026, 9);

    @Autowired
    private BudgetRepository budgetRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        new AuthUserFixture(jdbcTemplate).reset(MEMBER_A, MEMBER_B);
    }

    @Test
    @DisplayName("회원·달 조회는 그 회원의 그 달 행만 몫과 함께 읽는다 — 같은 달 B 의 행·A 의 다른 달 행은 안 나온다")
    void find_by_member_and_month_reads_only_that_members_month() {
        Budget memberASeptember = budgetRepository.save(budget(
                MEMBER_A, SEPTEMBER, List.of(new GroupAmount(PaymentGroup.CREDIT_CARD, new BigDecimal("300.00"))), 1L));
        budgetRepository.save(budget(MEMBER_A, SEPTEMBER.plusMonths(1), List.of()));
        Budget memberBSeptember = budgetRepository.save(budget(MEMBER_B, SEPTEMBER, List.of(), 2L));
        entityManager.flush();
        entityManager.clear();

        Budget loaded = budgetRepository
                .findByMemberIdAndMonth(MEMBER_A, SEPTEMBER.atDay(1))
                .orElseThrow();

        assertThat(loaded.getId()).isEqualTo(memberASeptember.getId());
        assertThat(loaded.amounts().paymentGroupAmounts()).containsOnlyKeys(PaymentGroup.CREDIT_CARD);
        assertThat(loaded.amounts().categoryAmounts()).containsOnlyKeys(1L);
        assertThat(budgetRepository.findByMemberIdAndMonth(MEMBER_B, SEPTEMBER.atDay(1)))
                .map(Budget::getId)
                .contains(memberBSeptember.getId());
        assertThat(budgetRepository.findByMemberIdAndMonth(
                        MEMBER_A, SEPTEMBER.plusMonths(2).atDay(1)))
                .isEmpty();
    }

    @Test
    @DisplayName("회원·달 조회는 예산 행과 카테고리 몫을 SQL 한 번에 읽고, 결제수단 몫·카테고리 몫이 저장한 그대로다")
    void find_by_member_and_month_reads_budget_and_category_shares_in_one_statement() {
        budgetRepository.save(budget(
                MEMBER_A,
                SEPTEMBER,
                List.of(
                        new GroupAmount(PaymentGroup.CREDIT_CARD, new BigDecimal("300.00")),
                        new GroupAmount(PaymentGroup.ACCOUNT_AND_OTHER, new BigDecimal("200.00"))),
                1L,
                2L));
        entityManager.flush();
        entityManager.clear();
        Statistics stats = entityManager
                .getEntityManagerFactory()
                .unwrap(SessionFactory.class)
                .getStatistics();
        boolean statisticsEnabled = stats.isStatisticsEnabled();
        stats.setStatisticsEnabled(true);
        stats.clear();
        try {
            BudgetAmounts amounts = budgetRepository
                    .findByMemberIdAndMonth(MEMBER_A, SEPTEMBER.atDay(1))
                    .orElseThrow()
                    .amounts();

            assertThat(amounts.total()).isEqualByComparingTo("1000.00");
            assertThat(amounts.paymentGroupAmounts())
                    .containsOnlyKeys(PaymentGroup.CREDIT_CARD, PaymentGroup.ACCOUNT_AND_OTHER);
            assertThat(amounts.paymentGroupAmounts().get(PaymentGroup.CREDIT_CARD))
                    .isEqualByComparingTo("300.00");
            assertThat(amounts.paymentGroupAmounts().get(PaymentGroup.ACCOUNT_AND_OTHER))
                    .isEqualByComparingTo("200.00");
            assertThat(amounts.categoryAmounts()).containsOnlyKeys(1L, 2L);
            assertThat(amounts.categoryAmounts().get(1L)).isEqualByComparingTo("100.00");
            assertThat(amounts.categoryAmounts().get(2L)).isEqualByComparingTo("100.00");
            assertThat(stats.getPrepareStatementCount()).isEqualTo(1);
        } finally {
            stats.setStatisticsEnabled(statisticsEnabled);
        }
    }

    @Test
    @DisplayName("hasAnyBudget 은 그 회원의 행만 센다")
    void exists_by_member_id_is_scoped_to_member() {
        assertThat(budgetRepository.existsByMemberId(MEMBER_A)).isFalse();

        budgetRepository.saveAndFlush(budget(MEMBER_A, SEPTEMBER.minusMonths(1), List.of()));

        assertThat(budgetRepository.existsByMemberId(MEMBER_A)).isTrue();
        assertThat(budgetRepository.existsByMemberId(MEMBER_B)).isFalse();
    }

    @Test
    @DisplayName("회원 A 의 예산은 회원 B 로 조회·삭제되지 않는다")
    void member_b_cannot_read_or_delete_member_a_rows() {
        budgetRepository.save(budget(MEMBER_A, SEPTEMBER, List.of()));
        budgetRepository.save(budget(MEMBER_A, SEPTEMBER.plusMonths(1), List.of()));
        entityManager.flush();

        assertThat(budgetRepository.findByMemberIdAndMonth(MEMBER_B, SEPTEMBER.atDay(1)))
                .isEmpty();
        assertThat(budgetRepository.existsByMemberId(MEMBER_B)).isFalse();
        assertThat(budgetRepository.deleteAllByMemberId(MEMBER_B)).isZero();

        assertThat(budgetRowCount(MEMBER_A)).isEqualTo(2);
        assertThat(budgetRepository.findByMemberIdAndMonth(MEMBER_A, SEPTEMBER.atDay(1)))
                .isPresent();
    }

    @Test
    @DisplayName("같은 (회원, 달) 은 두 번 저장할 수 없다")
    void duplicate_member_month_is_rejected() {
        budgetRepository.saveAndFlush(budget(MEMBER_A, SEPTEMBER, List.of()));

        assertThatThrownBy(() -> budgetRepository.saveAndFlush(budget(MEMBER_A, SEPTEMBER, List.of())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("회원 전체 삭제는 그 회원의 예산과 몫만 지운다")
    void delete_all_by_member_id_removes_budgets_and_allocations() {
        List<GroupAmount> cashAndDebit = List.of(new GroupAmount(PaymentGroup.CASH_AND_DEBIT, new BigDecimal("10.00")));
        budgetRepository.save(budget(MEMBER_A, SEPTEMBER, cashAndDebit, 1L));
        budgetRepository.save(budget(MEMBER_B, SEPTEMBER, cashAndDebit, 1L));
        entityManager.flush();

        int deleted = budgetRepository.deleteAllByMemberId(MEMBER_A);

        assertThat(deleted).isEqualTo(1);
        assertThat(budgetRowCount(MEMBER_A)).isZero();
        assertThat(budgetRowCount(MEMBER_B)).isEqualTo(1);
        assertThat(allocationCount()).isEqualTo(1);
    }

    private static Budget budget(UUID memberId, YearMonth month, List<GroupAmount> groups, long... categoryIds) {
        Budget budget = new Budget(memberId, month);
        budget.replaceAmounts(
                CurrencyCode.KRW,
                new BigDecimal("1000.00"),
                groups,
                Arrays.stream(categoryIds)
                        .mapToObj(id -> new CategoryAmount(id, new BigDecimal("100.00")))
                        .toList());
        return budget;
    }

    private int budgetRowCount(UUID memberId) {
        Integer count =
                jdbcTemplate.queryForObject("select count(*) from budget where member_id = ?", Integer.class, memberId);
        return count == null ? 0 : count;
    }

    private int allocationCount() {
        Integer count = jdbcTemplate.queryForObject("select count(*) from budget_category_allocation", Integer.class);
        return count == null ? 0 : count;
    }
}

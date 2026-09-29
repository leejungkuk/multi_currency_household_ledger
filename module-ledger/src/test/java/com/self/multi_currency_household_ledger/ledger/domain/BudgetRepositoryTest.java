package com.self.multi_currency_household_ledger.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace.NONE;

import com.self.multi_currency_household_ledger.exchange.domain.CurrencyCode;
import com.self.multi_currency_household_ledger.ledger.AuthUserFixture;
import com.self.multi_currency_household_ledger.ledger.TestJpaConfig;
import com.self.multi_currency_household_ledger.ledger.TestLedgerApplication;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
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
    @DisplayName("달 M 해석 쿼리는 두 축의 (MONTH, M) 와 (DEFAULT, ≤M) 를 몫과 함께 읽고 늦은 DEFAULT·다른 달 MONTH 는 뺀다")
    void find_for_month_reads_month_row_and_earlier_defaults_only() {
        Budget earlyDefault = budgetRepository.save(budget(
                MEMBER_A,
                TransactionType.EXPENSE,
                BudgetKind.DEFAULT,
                SEPTEMBER.minusMonths(2),
                List.of(BudgetAllocation.forPaymentGroup(PaymentGroup.CREDIT_CARD, new BigDecimal("300.00")))));
        Budget sameMonthDefault = budgetRepository.save(
                budget(MEMBER_A, TransactionType.INCOME, BudgetKind.DEFAULT, SEPTEMBER, List.of()));
        Budget monthRow = budgetRepository.save(
                budget(MEMBER_A, TransactionType.EXPENSE, BudgetKind.MONTH, SEPTEMBER, List.of()));
        budgetRepository.save(
                budget(MEMBER_A, TransactionType.EXPENSE, BudgetKind.DEFAULT, SEPTEMBER.plusMonths(1), List.of()));
        budgetRepository.save(
                budget(MEMBER_A, TransactionType.EXPENSE, BudgetKind.MONTH, SEPTEMBER.plusMonths(1), List.of()));
        budgetRepository.save(
                budget(MEMBER_A, TransactionType.EXPENSE, BudgetKind.MONTH, SEPTEMBER.minusMonths(1), List.of()));
        entityManager.flush();
        entityManager.clear();

        List<Budget> budgets = budgetRepository.findForMonth(MEMBER_A, SEPTEMBER.atDay(1));

        assertThat(budgets)
                .extracting(Budget::getId)
                .containsExactlyInAnyOrder(earlyDefault.getId(), sameMonthDefault.getId(), monthRow.getId());
        Budget loadedDefault = budgets.stream()
                .filter(b -> b.getId().equals(earlyDefault.getId()))
                .findFirst()
                .orElseThrow();
        assertThat(loadedDefault.getAllocations())
                .extracting(BudgetAllocation::getPaymentGroup)
                .containsExactly(PaymentGroup.CREDIT_CARD);
    }

    @Test
    @DisplayName("회원 A 의 예산은 회원 B 로 조회·삭제되지 않는다")
    void member_b_cannot_read_or_delete_member_a_rows() {
        budgetRepository.save(budget(MEMBER_A, TransactionType.EXPENSE, BudgetKind.MONTH, SEPTEMBER, List.of()));
        budgetRepository.save(
                budget(MEMBER_A, TransactionType.EXPENSE, BudgetKind.DEFAULT, SEPTEMBER.plusMonths(1), List.of()));
        entityManager.flush();

        assertThat(budgetRepository.findForMonth(
                        MEMBER_B, SEPTEMBER.plusMonths(1).atDay(1)))
                .isEmpty();
        assertThat(budgetRepository.findByMemberIdAndAxisAndKindAndMonth(
                        MEMBER_B, TransactionType.EXPENSE, BudgetKind.MONTH, SEPTEMBER.atDay(1)))
                .isEmpty();
        assertThat(budgetRepository.existsByMemberIdAndTotalAmountIsNotNull(MEMBER_B))
                .isFalse();
        assertThat(budgetRepository.deleteDefaultsAfter(MEMBER_B, TransactionType.EXPENSE, SEPTEMBER.atDay(1)))
                .isZero();
        assertThat(budgetRepository.deleteAllByMemberId(MEMBER_B)).isZero();

        assertThat(budgetRowCount(MEMBER_A)).isEqualTo(2);
        assertThat(budgetRepository.findByMemberIdAndAxisAndKindAndMonth(
                        MEMBER_A, TransactionType.EXPENSE, BudgetKind.MONTH, SEPTEMBER.atDay(1)))
                .isPresent();
    }

    @Test
    @DisplayName("같은 (회원, 축, 종류, 달) 은 두 번 저장할 수 없다")
    void duplicate_member_axis_kind_month_is_rejected() {
        budgetRepository.saveAndFlush(
                budget(MEMBER_A, TransactionType.EXPENSE, BudgetKind.MONTH, SEPTEMBER, List.of()));

        assertThatThrownBy(() -> budgetRepository.saveAndFlush(
                        budget(MEMBER_A, TransactionType.EXPENSE, BudgetKind.MONTH, SEPTEMBER, List.of())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("늦은 DEFAULT 삭제는 그 축의 month > M DEFAULT 만 지우고 MONTH·같은 달·다른 축은 남긴다")
    void delete_defaults_after_removes_only_later_defaults_of_axis() {
        budgetRepository.save(budget(MEMBER_A, TransactionType.EXPENSE, BudgetKind.DEFAULT, SEPTEMBER, List.of()));
        budgetRepository.save(budget(
                MEMBER_A,
                TransactionType.EXPENSE,
                BudgetKind.DEFAULT,
                SEPTEMBER.plusMonths(1),
                List.of(BudgetAllocation.forCategory(1L, new BigDecimal("100.00")))));
        budgetRepository.save(
                budget(MEMBER_A, TransactionType.EXPENSE, BudgetKind.MONTH, SEPTEMBER.plusMonths(1), List.of()));
        budgetRepository.save(
                budget(MEMBER_A, TransactionType.INCOME, BudgetKind.DEFAULT, SEPTEMBER.plusMonths(1), List.of()));
        entityManager.flush();

        int deleted = budgetRepository.deleteDefaultsAfter(MEMBER_A, TransactionType.EXPENSE, SEPTEMBER.atDay(1));

        assertThat(deleted).isEqualTo(1);
        assertThat(budgetRowCount(MEMBER_A)).isEqualTo(3);
        assertThat(budgetRepository.findByMemberIdAndAxisAndKindAndMonth(
                        MEMBER_A,
                        TransactionType.EXPENSE,
                        BudgetKind.DEFAULT,
                        SEPTEMBER.plusMonths(1).atDay(1)))
                .isEmpty();
        assertThat(allocationCount()).isZero();
    }

    @Test
    @DisplayName("금액이 있는 행이 있을 때만 hasAnyBudget 이 참이다 — 끔 행은 세지 않는다")
    void exists_with_total_ignores_off_rows() {
        budgetRepository.saveAndFlush(
                new Budget(MEMBER_A, TransactionType.EXPENSE, BudgetKind.MONTH, SEPTEMBER, null, null, List.of()));

        assertThat(budgetRepository.existsByMemberIdAndTotalAmountIsNotNull(MEMBER_A))
                .isFalse();

        budgetRepository.saveAndFlush(
                budget(MEMBER_A, TransactionType.EXPENSE, BudgetKind.DEFAULT, SEPTEMBER, List.of()));

        assertThat(budgetRepository.existsByMemberIdAndTotalAmountIsNotNull(MEMBER_A))
                .isTrue();
    }

    @Test
    @DisplayName("회원 전체 삭제는 그 회원의 예산과 몫만 지운다")
    void delete_all_by_member_id_removes_budgets_and_allocations() {
        budgetRepository.save(budget(
                MEMBER_A,
                TransactionType.EXPENSE,
                BudgetKind.MONTH,
                SEPTEMBER,
                List.of(BudgetAllocation.forPaymentGroup(PaymentGroup.CASH_AND_DEBIT, new BigDecimal("10.00")))));
        budgetRepository.save(budget(
                MEMBER_B,
                TransactionType.EXPENSE,
                BudgetKind.MONTH,
                SEPTEMBER,
                List.of(BudgetAllocation.forPaymentGroup(PaymentGroup.CASH_AND_DEBIT, new BigDecimal("10.00")))));
        entityManager.flush();

        int deleted = budgetRepository.deleteAllByMemberId(MEMBER_A);

        assertThat(deleted).isEqualTo(1);
        assertThat(budgetRowCount(MEMBER_A)).isZero();
        assertThat(budgetRowCount(MEMBER_B)).isEqualTo(1);
        assertThat(allocationCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("카테고리 몫 삭제는 그 회원의 month ≥ C 행에서 그 카테고리 몫만 지우고 이전 달·다른 카테고리·다른 회원은 남긴다")
    void delete_category_allocations_from_is_scoped_to_member_month_and_category() {
        long target = 1L;
        long other = 2L;
        budgetRepository.save(budget(
                MEMBER_A,
                TransactionType.EXPENSE,
                BudgetKind.DEFAULT,
                SEPTEMBER.minusMonths(1),
                categoryAmounts(target)));
        budgetRepository.save(
                budget(MEMBER_A, TransactionType.EXPENSE, BudgetKind.MONTH, SEPTEMBER, categoryAmounts(target, other)));
        budgetRepository.save(budget(
                MEMBER_A,
                TransactionType.EXPENSE,
                BudgetKind.DEFAULT,
                SEPTEMBER.plusMonths(1),
                categoryAmounts(target)));
        budgetRepository.save(
                budget(MEMBER_B, TransactionType.EXPENSE, BudgetKind.MONTH, SEPTEMBER, categoryAmounts(target)));
        entityManager.flush();

        int deleted = budgetRepository.deleteCategoryAllocationsFrom(MEMBER_A, target, SEPTEMBER.atDay(1));

        assertThat(deleted).isEqualTo(2);
        assertThat(categoryAllocationMonths(MEMBER_A, target)).containsExactly("2026-08-01");
        assertThat(categoryAllocationMonths(MEMBER_A, other)).containsExactly("2026-09-01");
        assertThat(categoryAllocationMonths(MEMBER_B, target)).containsExactly("2026-09-01");
    }

    @Test
    @DisplayName("C 이전 최신 DEFAULT 조회는 그 회원·축의 month < C 중 가장 늦은 DEFAULT 만 고른다")
    void find_latest_default_before_picks_latest_earlier_default_of_member_axis() {
        budgetRepository.save(
                budget(MEMBER_A, TransactionType.EXPENSE, BudgetKind.DEFAULT, SEPTEMBER.minusMonths(3), List.of()));
        Budget latest = budgetRepository.save(
                budget(MEMBER_A, TransactionType.EXPENSE, BudgetKind.DEFAULT, SEPTEMBER.minusMonths(1), List.of()));
        budgetRepository.save(budget(MEMBER_A, TransactionType.EXPENSE, BudgetKind.DEFAULT, SEPTEMBER, List.of()));
        budgetRepository.save(
                budget(MEMBER_A, TransactionType.EXPENSE, BudgetKind.MONTH, SEPTEMBER.minusMonths(1), List.of()));
        budgetRepository.save(
                budget(MEMBER_A, TransactionType.INCOME, BudgetKind.DEFAULT, SEPTEMBER.minusMonths(1), List.of()));
        entityManager.flush();

        assertThat(budgetRepository.findFirstByMemberIdAndAxisAndKindAndMonthLessThanOrderByMonthDesc(
                        MEMBER_A, TransactionType.EXPENSE, BudgetKind.DEFAULT, SEPTEMBER.atDay(1)))
                .map(Budget::getId)
                .contains(latest.getId());
        assertThat(budgetRepository.findFirstByMemberIdAndAxisAndKindAndMonthLessThanOrderByMonthDesc(
                        MEMBER_B, TransactionType.EXPENSE, BudgetKind.DEFAULT, SEPTEMBER.atDay(1)))
                .isEmpty();
    }

    private static List<BudgetAllocation> categoryAmounts(long... categoryIds) {
        return Arrays.stream(categoryIds)
                .mapToObj(id -> BudgetAllocation.forCategory(id, new BigDecimal("100.00")))
                .toList();
    }

    private List<String> categoryAllocationMonths(UUID memberId, long categoryId) {
        return jdbcTemplate.queryForList(
                """
                select cast(b.month as text) from budget_allocation a join budget b on b.id = a.budget_id
                where b.member_id = ? and a.category_id = ? order by b.month
                """,
                String.class,
                memberId,
                categoryId);
    }

    private static Budget budget(
            UUID memberId, TransactionType axis, BudgetKind kind, YearMonth month, List<BudgetAllocation> allocations) {
        return new Budget(memberId, axis, kind, month, CurrencyCode.KRW, new BigDecimal("1000.00"), allocations);
    }

    private int budgetRowCount(UUID memberId) {
        Integer count =
                jdbcTemplate.queryForObject("select count(*) from budget where member_id = ?", Integer.class, memberId);
        return count == null ? 0 : count;
    }

    private int allocationCount() {
        Integer count = jdbcTemplate.queryForObject("select count(*) from budget_allocation", Integer.class);
        return count == null ? 0 : count;
    }
}

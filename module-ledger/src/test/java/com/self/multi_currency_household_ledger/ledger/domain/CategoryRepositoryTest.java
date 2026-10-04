package com.self.multi_currency_household_ledger.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace.NONE;

import com.self.multi_currency_household_ledger.exchange.domain.CurrencyCode;
import com.self.multi_currency_household_ledger.ledger.AuthUserFixture;
import com.self.multi_currency_household_ledger.ledger.TestJpaConfig;
import com.self.multi_currency_household_ledger.ledger.TestLedgerApplication;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@Import({TestLedgerApplication.class, TestJpaConfig.class})
class CategoryRepositoryTest {

    private static final UUID MEMBER_A = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID MEMBER_B = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final LocalDate SEPTEMBER = LocalDate.of(2026, 9, 1);
    private static final LocalDate OCTOBER = LocalDate.of(2026, 10, 1);
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-10-15T03:00:00Z"), ZoneId.of("Asia/Seoul"));

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Autowired
    private AssetRepository assetRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        new AuthUserFixture(jdbcTemplate).reset(MEMBER_A, MEMBER_B);
    }

    // 공용 고정 카탈로그의 활성화된 카테고리 목록을 조회한다.
    @Test
    @DisplayName("공용 활성화 카테고리를 타입별로 sort_order 순서로 조회할 수 있다")
    void find_categories_by_type_as_shared_catalog() {
        categoryRepository.save(new Category(TransactionType.EXPENSE, "TEST_FOOD", "식비", "Food", "icon-food", 100));
        categoryRepository.save(new Category(TransactionType.EXPENSE, "TEST_CAFE", "카페", "Cafe", "icon-cafe", 101));
        categoryRepository.save(
                new Category(TransactionType.INCOME, "TEST_SALARY", "급여", "Salary", "icon-salary", 100));

        List<Category> categories =
                categoryRepository.findByOwnerMemberIdIsNullAndTransactionTypeAndIsActiveTrueOrderBySortOrder(
                        TransactionType.EXPENSE);

        assertThat(categories).extracting(Category::getCode).containsSubsequence("TEST_FOOD", "TEST_CAFE");
        assertThat(categories).noneMatch(category -> category.getCode().equals("TEST_SALARY"));
    }

    @Test
    @DisplayName("공개 카테고리 목록은 회원 소유 커스텀 카테고리를 노출하지 않는다")
    void shared_catalog_excludes_custom_categories() {
        categoryRepository.saveAndFlush(Category.custom(MEMBER_A, TransactionType.EXPENSE, "반려견", "🐶"));

        List<Category> categories =
                categoryRepository.findByOwnerMemberIdIsNullAndTransactionTypeAndIsActiveTrueOrderBySortOrder(
                        TransactionType.EXPENSE);

        assertThat(categories).allMatch(category -> category.getOwnerMemberId() == null);
        assertThat(categories).noneMatch(category -> category.getDisplayNameKo().equals("반려견"));
    }

    @Test
    @DisplayName("커스텀 목록과 count는 내 활성 행만 반환하고 소유자 단건 조회는 타 회원을 숨긴다")
    void custom_catalog_queries_are_owner_scoped_and_exclude_inactive_rows() {
        Category first =
                categoryRepository.saveAndFlush(Category.custom(MEMBER_A, TransactionType.EXPENSE, "반려견", "🐶"));
        Category second =
                categoryRepository.saveAndFlush(Category.custom(MEMBER_A, TransactionType.EXPENSE, "어학원", "📚"));
        Category inactive = Category.custom(MEMBER_A, TransactionType.EXPENSE, "숨김", null);
        inactive.deactivate();
        categoryRepository.saveAndFlush(inactive);
        categoryRepository.saveAndFlush(Category.custom(MEMBER_B, TransactionType.EXPENSE, "타 회원", null));
        categoryRepository.saveAndFlush(Category.custom(MEMBER_A, TransactionType.INCOME, "부수입", null));

        List<Category> categories = customCategories();

        assertThat(categories).extracting(Category::getId).containsExactly(second.getId(), first.getId());
        assertThat(categoryRepository.countByOwnerMemberIdAndIsActiveTrue(MEMBER_A))
                .isEqualTo(3L);
        assertThat(categoryRepository.findByIdAndOwnerMemberId(first.getId(), MEMBER_A))
                .contains(first);
        assertThat(categoryRepository.findByIdAndOwnerMemberId(first.getId(), MEMBER_B))
                .isEmpty();
    }

    @Test
    @DisplayName("커스텀 목록은 sort_order 오름차순·id 내림차순으로 정렬하고 재정렬 값을 반영한다")
    void custom_catalog_orders_by_sort_order_then_id_desc() {
        Category first =
                categoryRepository.saveAndFlush(Category.custom(MEMBER_A, TransactionType.EXPENSE, "첫째", null));
        Category second =
                categoryRepository.saveAndFlush(Category.custom(MEMBER_A, TransactionType.EXPENSE, "둘째", null));
        Category third =
                categoryRepository.saveAndFlush(Category.custom(MEMBER_A, TransactionType.EXPENSE, "셋째", null));

        assertThat(customCategories())
                .extracting(Category::getId)
                .containsExactly(third.getId(), second.getId(), first.getId());

        first.applySortOrder(1001);
        second.applySortOrder(1002);
        categoryRepository.saveAllAndFlush(List.of(first, second));

        assertThat(customCategories())
                .extracting(Category::getId)
                .containsExactly(third.getId(), first.getId(), second.getId());
    }

    @Test
    @DisplayName("수정 대상 조회는 내 활성 커스텀만 반환하고 비활성·타 회원·시스템 행을 숨긴다")
    void find_editable_custom_category_applies_owner_and_active_predicates() {
        Category mine =
                categoryRepository.saveAndFlush(Category.custom(MEMBER_A, TransactionType.EXPENSE, "반려견", "🐶"));
        Category other =
                categoryRepository.saveAndFlush(Category.custom(MEMBER_B, TransactionType.EXPENSE, "타 회원", null));
        Category inactive = Category.custom(MEMBER_A, TransactionType.EXPENSE, "숨김", null);
        inactive.deactivate();
        categoryRepository.saveAndFlush(inactive);

        assertThat(categoryRepository.findByIdAndOwnerMemberIdAndIsActiveTrue(mine.getId(), MEMBER_A))
                .contains(mine);
        assertThat(categoryRepository.findByIdAndOwnerMemberIdAndIsActiveTrue(inactive.getId(), MEMBER_A))
                .isEmpty();
        assertThat(categoryRepository.findByIdAndOwnerMemberIdAndIsActiveTrue(other.getId(), MEMBER_A))
                .isEmpty();
        assertThat(categoryRepository.findByIdAndOwnerMemberIdAndIsActiveTrue(1L, MEMBER_A))
                .isEmpty();
    }

    @Test
    @DisplayName("사용 가능 카테고리는 시스템 또는 내 활성 커스텀만 허용한다")
    void find_usable_category_applies_owner_and_active_predicates() {
        Category mine =
                categoryRepository.saveAndFlush(Category.custom(MEMBER_A, TransactionType.EXPENSE, "반려견", "🐶"));
        Category other =
                categoryRepository.saveAndFlush(Category.custom(MEMBER_B, TransactionType.EXPENSE, "타 회원", null));
        Category inactive = Category.custom(MEMBER_A, TransactionType.EXPENSE, "숨김", null);
        inactive.deactivate();
        categoryRepository.saveAndFlush(inactive);

        assertThat(categoryRepository.findUsableCategory(1L, MEMBER_A)).isPresent();
        assertThat(categoryRepository.findUsableCategory(mine.getId(), MEMBER_A))
                .contains(mine);
        assertThat(categoryRepository.findUsableCategory(other.getId(), MEMBER_A))
                .isEmpty();
        assertThat(categoryRepository.findUsableCategory(inactive.getId(), MEMBER_A))
                .isEmpty();
    }

    @Test
    @DisplayName("같은 타입과 CUSTOM 코드를 가진 커스텀 카테고리를 여러 건 저장할 수 있다")
    void custom_categories_allow_duplicate_type_and_code() {
        Category first =
                categoryRepository.saveAndFlush(Category.custom(MEMBER_A, TransactionType.EXPENSE, "반려견", "🐶"));
        Category second =
                categoryRepository.saveAndFlush(Category.custom(MEMBER_A, TransactionType.EXPENSE, "반려견", "🐶"));

        assertThat(first.getCode()).isEqualTo("CUSTOM");
        assertThat(second.getCode()).isEqualTo("CUSTOM");
        assertThat(second.getId()).isGreaterThan(first.getId());
    }

    @Test
    @DisplayName("소유자 커스텀 카테고리 물리 삭제는 활성·비활성을 모두 지우고 시스템·타 회원 행은 보존한다")
    void delete_all_by_owner_member_id_removes_owner_rows_only() {
        Category active =
                categoryRepository.saveAndFlush(Category.custom(MEMBER_A, TransactionType.EXPENSE, "활성", "🐶"));
        Category inactive = Category.custom(MEMBER_A, TransactionType.INCOME, "비활성", null);
        inactive.deactivate();
        categoryRepository.saveAndFlush(inactive);
        Category otherMember =
                categoryRepository.saveAndFlush(Category.custom(MEMBER_B, TransactionType.EXPENSE, "타 회원", null));
        long systemCategoriesBefore = systemCategoryCount();

        int deleted = categoryRepository.deleteAllByOwnerMemberId(MEMBER_A);

        assertThat(deleted).isEqualTo(2);
        assertThat(categoryRepository.findById(active.getId())).isEmpty();
        assertThat(categoryRepository.findById(inactive.getId())).isEmpty();
        assertThat(categoryRepository.findById(otherMember.getId())).isPresent();
        assertThat(systemCategoryCount()).isEqualTo(systemCategoriesBefore);
    }

    @Test
    @DisplayName("고아 정리는 예산 몫이 참조하는 비활성 카테고리를 남기고, 참조 없는 비활성 카테고리만 지운다")
    void delete_orphaned_inactive_keeps_categories_referenced_by_budget_allocation() {
        Category referenced = Category.custom(MEMBER_A, TransactionType.EXPENSE, "지난 달 몫", null);
        referenced.deactivate();
        categoryRepository.saveAndFlush(referenced);
        Category orphan = Category.custom(MEMBER_A, TransactionType.EXPENSE, "고아", null);
        orphan.deactivate();
        categoryRepository.saveAndFlush(orphan);
        Long budgetId = jdbcTemplate.queryForObject(
                """
                insert into budget (member_id, month, currency_code, total_amount, created_at, updated_at)
                values (?, date '2026-08-01', 'KRW', 1000, now(), now()) returning id
                """,
                Long.class,
                MEMBER_A);
        jdbcTemplate.update(
                "insert into budget_category_allocation (budget_id, category_id, amount) values (?, ?, 100)",
                budgetId,
                referenced.getId());

        int deleted = categoryRepository.deleteOrphanedInactive(MEMBER_A, LocalDateTime.of(2999, 1, 1, 0, 0));

        assertThat(deleted).isEqualTo(1);
        assertThat(categoryRepository.findById(orphan.getId())).isEmpty();
        assertThat(categoryRepository.findById(referenced.getId())).isPresent();
        assertThat(jdbcTemplate.queryForObject(
                        "select count(*) from budget_category_allocation where category_id = ?",
                        Long.class,
                        referenced.getId()))
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("그 달에 이 회원의 지출 거래가 있는 삭제된 지출 카테고리만 sort_order·id 순으로 돌려준다")
    void find_deleted_with_expenses_returns_members_deleted_expense_categories_spent_in_month() {
        DeletedSpendingFixture fixture = deletedSpendingFixture();

        assertThat(categoryRepository.findDeletedWithExpenses(MEMBER_A, SEPTEMBER, OCTOBER))
                .extracting(Category::getId)
                .containsExactly(fixture.w2().getId(), fixture.w().getId());
    }

    @Test
    @DisplayName("삭제된 카테고리 판정은 그 회원의 카테고리·거래로만 한다 — A 의 거래는 B 의 판정에 쓰이지 않는다")
    void find_deleted_with_expenses_is_scoped_to_member() {
        DeletedSpendingFixture fixture = deletedSpendingFixture();

        assertThat(categoryRepository.findDeletedWithExpenses(MEMBER_B, SEPTEMBER, OCTOBER))
                .extracting(Category::getId)
                .containsExactly(fixture.z().getId());
    }

    @Test
    @DisplayName("정렬값이 같은 삭제 카테고리는 id 오름차순으로 돌려준다 — 커스텀은 정렬값 기본값이 같아 동률이 흔하다")
    void find_deleted_with_expenses_breaks_sort_order_ties_by_id() {
        Asset asset = assetRepository.save(new Asset("TEST_CASH", "테스트 현금", "Test Cash", 100));
        // id 가 큰 쪽을 먼저 넣어 물리 순서를 id 순서와 반대로 둔다 — 보조 정렬이 없으면 이 순서로 나올 수 있다.
        Category larger = deletedCategoryWithId(20002L);
        Category smaller = deletedCategoryWithId(20001L);
        entry(MEMBER_A, larger, asset, "2026-09-10");
        entry(MEMBER_A, smaller, asset, "2026-09-10");
        ledgerEntryRepository.flush();

        assertThat(categoryRepository.findDeletedWithExpenses(MEMBER_A, SEPTEMBER, OCTOBER))
                .extracting(Category::getId)
                .containsExactly(20001L, 20002L);
    }

    @Test
    @DisplayName("비활성 시스템 카테고리(V6 LEGACY_CATEGORY_*)도 그 회원의 그 달 지출이 있으면 돌려준다 — 거래가 없는 회원에게는 안 나온다")
    void find_deleted_with_expenses_includes_inactive_system_category() {
        Asset asset = assetRepository.save(new Asset("TEST_CASH", "테스트 현금", "Test Cash", 100));
        // 시스템 예약 범위(22~9999, V11) 안의 id 로 owner_member_id 가 null 인 비활성 지출 카테고리를 넣는다.
        jdbcTemplate.update(
                """
                insert into category (id, transaction_type, code, display_name_ko, display_name_en, sort_order,
                                      owner_member_id, is_active)
                values (9001, 'EXPENSE', 'TEST_LEGACY_S', '옛 시스템', 'Legacy', 1000, null, false)
                """);
        Category system = categoryRepository.findById(9001L).orElseThrow();
        entry(MEMBER_A, system, asset, "2026-09-10");
        ledgerEntryRepository.flush();

        assertThat(categoryRepository.findDeletedWithExpenses(MEMBER_A, SEPTEMBER, OCTOBER))
                .extracting(Category::getId)
                .contains(9001L);
        assertThat(categoryRepository.findDeletedWithExpenses(MEMBER_B, SEPTEMBER, OCTOBER))
                .extracting(Category::getId)
                .doesNotContain(9001L);
    }

    private record DeletedSpendingFixture(Category w, Category w2, Category z) {}

    /**
     * 회원 A: 삭제된 W(9/30 지출)·W2(9/1 지출, W 보다 늦게 만들었지만 정렬값이 작다)·V(8/31·10/1 지출만)·U(거래 없음)·수입 I(9월 수입),
     * 활성 X(9월 지출). 회원 B: 삭제된 Z(B 의 9월 지출), 삭제된 Z2(A 의 9월 지출만 — 서비스로는 못 만드는 행이지만 카테고리 소유 술어와 거래
     * member_id 술어를 시험하려고 넣는다).
     */
    private DeletedSpendingFixture deletedSpendingFixture() {
        Asset asset = assetRepository.save(new Asset("TEST_CASH", "테스트 현금", "Test Cash", 100));
        Category w = deletedCategory(MEMBER_A, TransactionType.EXPENSE, "W", 1000);
        Category w2 = deletedCategory(MEMBER_A, TransactionType.EXPENSE, "W2", 999);
        Category v = deletedCategory(MEMBER_A, TransactionType.EXPENSE, "V", 1000);
        deletedCategory(MEMBER_A, TransactionType.EXPENSE, "U", 1000);
        Category i = deletedCategory(MEMBER_A, TransactionType.INCOME, "I", 1000);
        Category x = categoryRepository.saveAndFlush(Category.custom(MEMBER_A, TransactionType.EXPENSE, "X", null));
        Category z = deletedCategory(MEMBER_B, TransactionType.EXPENSE, "Z", 1000);
        Category z2 = deletedCategory(MEMBER_B, TransactionType.EXPENSE, "Z2", 1000);
        entry(MEMBER_A, w, asset, "2026-09-30");
        entry(MEMBER_A, w2, asset, "2026-09-01");
        entry(MEMBER_A, v, asset, "2026-08-31");
        entry(MEMBER_A, v, asset, "2026-10-01");
        entry(MEMBER_A, i, asset, "2026-09-10");
        entry(MEMBER_A, x, asset, "2026-09-10");
        entry(MEMBER_B, z, asset, "2026-09-10");
        entry(MEMBER_A, z2, asset, "2026-09-10");
        ledgerEntryRepository.flush();
        return new DeletedSpendingFixture(w, w2, z);
    }

    private Category deletedCategory(UUID ownerMemberId, TransactionType type, String name, int sortOrder) {
        Category category = Category.custom(ownerMemberId, type, name, null);
        category.applySortOrder(sortOrder);
        category.deactivate();
        return categoryRepository.saveAndFlush(category);
    }

    /** id 를 지정해 넣은 회원 A 의 삭제된 지출 커스텀 카테고리(정렬값 1000 — Category.custom 기본값). */
    private Category deletedCategoryWithId(long id) {
        jdbcTemplate.update(
                """
                insert into category (id, transaction_type, code, display_name_ko, display_name_en, sort_order,
                                      owner_member_id, is_active)
                values (?, 'EXPENSE', 'CUSTOM', '동률', '동률', 1000, ?, false)
                """,
                id,
                MEMBER_A);
        return categoryRepository.findById(id).orElseThrow();
    }

    /** 카테고리 타입을 따르는 KRW 거래 한 건. */
    private void entry(UUID memberId, Category category, Asset asset, String date) {
        ledgerEntryRepository.save(LedgerEntry.of(
                memberId,
                category,
                asset,
                new BigDecimal("1000"),
                CurrencyCode.KRW,
                LocalDate.parse(date),
                null,
                null,
                FIXED_CLOCK));
    }

    private long systemCategoryCount() {
        Long count =
                jdbcTemplate.queryForObject("select count(*) from category where owner_member_id is null", Long.class);
        return count == null ? 0L : count;
    }

    private List<Category> customCategories() {
        return categoryRepository.findByOwnerMemberIdAndTransactionTypeAndIsActiveTrueOrderBySortOrderAscIdDesc(
                MEMBER_A, TransactionType.EXPENSE);
    }
}

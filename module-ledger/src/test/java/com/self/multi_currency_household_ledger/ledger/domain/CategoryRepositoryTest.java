package com.self.multi_currency_household_ledger.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace.NONE;

import com.self.multi_currency_household_ledger.ledger.AuthUserFixture;
import com.self.multi_currency_household_ledger.ledger.TestJpaConfig;
import com.self.multi_currency_household_ledger.ledger.TestLedgerApplication;
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

    @Autowired
    private CategoryRepository categoryRepository;

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

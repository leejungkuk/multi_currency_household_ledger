package com.self.multi_currency_household_ledger.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;

import com.self.multi_currency_household_ledger.ledger.TestJpaConfig;
import com.self.multi_currency_household_ledger.ledger.TestLedgerApplication;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@Import({TestLedgerApplication.class, TestJpaConfig.class})
class CategoryRepositoryTest {

    @Autowired
    private CategoryRepository categoryRepository;

    // 공용 고정 카탈로그의 활성화된 카테고리 목록을 조회한다.
    @Test
    @DisplayName("공용 활성화 카테고리를 타입별로 sort_order 순서로 조회할 수 있다")
    void find_categories_by_type_as_shared_catalog() {
        categoryRepository.save(new Category(TransactionType.EXPENSE, "TEST_FOOD", "식비", "Food", "icon-food", 100));
        categoryRepository.save(new Category(TransactionType.EXPENSE, "TEST_CAFE", "카페", "Cafe", "icon-cafe", 101));
        categoryRepository.save(
                new Category(TransactionType.INCOME, "TEST_SALARY", "급여", "Salary", "icon-salary", 100));

        List<Category> categories =
                categoryRepository.findByTransactionTypeAndIsActiveTrueOrderBySortOrder(TransactionType.EXPENSE);

        assertThat(categories).extracting(Category::getCode).containsSubsequence("TEST_FOOD", "TEST_CAFE");
        assertThat(categories).noneMatch(category -> category.getCode().equals("TEST_SALARY"));
    }
}

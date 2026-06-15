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

    // 특정 회원의 활성화된 카테고리 목록을 조회한다.
    @Test
    @DisplayName("소유자와 공통(0L) 소유의 활성화된 카테고리를 타입별로 조회할 수 있다")
    void find_categories_by_type_and_owner() {
        categoryRepository.save(new Category(TransactionType.EXPENSE, "FOOD", "식비", "icon-food", 1, 1L));
        categoryRepository.save(new Category(TransactionType.INCOME, "SALARY", "급여", "icon-salary", 1, 0L));

        List<Category> categories =
                categoryRepository.findByTransactionTypeAndOwnerMemberIdInAndIsActiveTrueOrderBySortOrder(
                        TransactionType.EXPENSE, List.of(0L, 1L));

        assertThat(categories).hasSize(1);
        assertThat(categories.get(0).getCode()).isEqualTo("FOOD");
    }
}

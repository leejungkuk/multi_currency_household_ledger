package com.self.multi_currency_household_ledger.ledger.seeder;

import com.self.multi_currency_household_ledger.ledger.domain.Asset;
import com.self.multi_currency_household_ledger.ledger.domain.AssetRepository;
import com.self.multi_currency_household_ledger.ledger.domain.Category;
import com.self.multi_currency_household_ledger.ledger.domain.CategoryRepository;
import com.self.multi_currency_household_ledger.ledger.domain.TransactionType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultCatalogSeeder implements CommandLineRunner {

    private final CategoryRepository categoryRepository;
    private final AssetRepository assetRepository;

    @Override
    public void run(String... args) {
        seedCategories();
        seedAssets();
    }

    private void seedCategories() {
        seedCategory(TransactionType.EXPENSE, "foodDining", "식비", "🍔", 1);
        seedCategory(TransactionType.EXPENSE, "cafeSnack", "카페/간식", "☕️", 2);
        seedCategory(TransactionType.EXPENSE, "transportation", "교통", "🚌", 3);
        seedCategory(TransactionType.EXPENSE, "shopping", "쇼핑", "🛍️", 4);
        seedCategory(TransactionType.EXPENSE, "dailyNecessity", "생활용품", "🛒", 5);
        seedCategory(TransactionType.EXPENSE, "leisureHobby", "여가/취미", "🎮", 6);
        seedCategory(TransactionType.EXPENSE, "healthFitness", "건강/의료", "💊", 7);
        seedCategory(TransactionType.EXPENSE, "housingUtility", "주거/통신", "🏠", 8);
        seedCategory(TransactionType.EXPENSE, "education", "교육", "📚", 9);
        seedCategory(TransactionType.EXPENSE, "giftCongratulatory", "경조사/선물", "🎁", 10);
        seedCategory(TransactionType.EXPENSE, "travel", "여행", "✈️", 11);
        seedCategory(TransactionType.EXPENSE, "otherExpense", "기타 지출", "📦", 12);

        seedCategory(TransactionType.INCOME, "salary", "월급", "💰", 1);
        seedCategory(TransactionType.INCOME, "sideJob", "부수입", "📈", 2);
        seedCategory(TransactionType.INCOME, "allowance", "용돈", "🧧", 3);
        seedCategory(TransactionType.INCOME, "bonus", "상여금", "🎉", 4);
        seedCategory(TransactionType.INCOME, "financialIncome", "금융소득", "🏦", 5);
        seedCategory(TransactionType.INCOME, "usedTrade", "중고거래", "♻️", 6);
        seedCategory(TransactionType.INCOME, "refund", "환불/캐시백", "💸", 7);
        seedCategory(TransactionType.INCOME, "otherIncome", "기타 수입", "📥", 8);
    }

    private void seedCategory(TransactionType type, String code, String name, String icon, int order) {
        if (!categoryRepository.existsByOwnerMemberIdAndTransactionTypeAndCode(Category.SYSTEM_OWNER_ID, type, code)) {
            categoryRepository.save(new Category(type, code, name, icon, order, Category.SYSTEM_OWNER_ID));
            log.info("Seeded default category: [{}] {}", type, code);
        }
    }

    private void seedAssets() {
        seedAsset("creditCard", "신용카드", "💳", 1);
        seedAsset("debitCard", "체크카드", "💳", 2);
        seedAsset("cash", "현금", "💵", 3);
        seedAsset("account", "은행계좌", "🏦", 4);
        seedAsset("check", "수표", "📜", 5);
        seedAsset("other", "기타", "📦", 6);
    }

    private void seedAsset(String code, String name, String icon, int order) {
        if (!assetRepository.existsByOwnerMemberIdAndCode(Asset.SYSTEM_OWNER_ID, code)) {
            assetRepository.save(new Asset(code, name, icon, order, Asset.SYSTEM_OWNER_ID));
            log.info("Seeded default asset: {}", code);
        }
    }
}

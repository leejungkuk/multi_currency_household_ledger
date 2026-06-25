package com.self.multi_currency_household_ledger.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;

import com.self.multi_currency_household_ledger.ledger.TestJpaConfig;
import com.self.multi_currency_household_ledger.ledger.TestLedgerApplication;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@Import({TestLedgerApplication.class, TestJpaConfig.class})
class CatalogSeedMigrationTest {

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private AssetRepository assetRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("Flyway 시드는 디자인 정본 카테고리 21개와 자산 6개를 명시 id로 저장한다")
    void catalog_seed_matches_design_canonical_rows() {
        List<Category> expenseCategories =
                categoryRepository.findByTransactionTypeAndIsActiveTrueOrderBySortOrder(TransactionType.EXPENSE);
        List<Category> incomeCategories =
                categoryRepository.findByTransactionTypeAndIsActiveTrueOrderBySortOrder(TransactionType.INCOME);
        List<Asset> assets = assetRepository.findByIsActiveTrueOrderBySortOrder();

        assertThat(expenseCategories)
                .extracting(
                        Category::getId,
                        Category::getCode,
                        Category::getDisplayNameKo,
                        Category::getDisplayNameEn,
                        Category::getIcon,
                        Category::getSortOrder)
                .containsExactly(
                        tuple(1L, "FOOD_DINING", "식비", "Food & Dining", "🍽️", 1),
                        tuple(2L, "CAFE_DRINKS", "카페/음료", "Café & Drinks", "☕", 2),
                        tuple(3L, "TRANSPORT", "교통", "Transport", "🚌", 3),
                        tuple(4L, "ACCOMMODATION", "숙박", "Accommodation", "🏨", 4),
                        tuple(5L, "GROCERIES", "식료품", "Groceries", "🛒", 5),
                        tuple(6L, "SHOPPING", "쇼핑", "Shopping", "🛍️", 6),
                        tuple(7L, "BEAUTY", "미용", "Beauty", "💇", 7),
                        tuple(8L, "ENTERTAINMENT", "여가/오락", "Entertainment", "🎭", 8),
                        tuple(9L, "HEALTH_MEDICAL", "건강/의료", "Health & Medical", "💊", 9),
                        tuple(10L, "SUBSCRIPTIONS", "구독", "Subscriptions", "📱", 10),
                        tuple(11L, "EDUCATION", "교육", "Education", "📚", 11),
                        tuple(12L, "TRAVEL", "여행", "Travel", "✈️", 12),
                        tuple(13L, "OTHER_EXPENSE", "기타", "Other", "📦", 13));
        assertThat(incomeCategories)
                .extracting(
                        Category::getId,
                        Category::getCode,
                        Category::getDisplayNameKo,
                        Category::getDisplayNameEn,
                        Category::getIcon,
                        Category::getSortOrder)
                .containsExactly(
                        tuple(14L, "SALARY", "급여", "Salary", "💼", 1),
                        tuple(15L, "SIDE_INCOME", "부수입", "Side Income", "💻", 2),
                        tuple(16L, "ALLOWANCE", "용돈", "Allowance", "🪙", 3),
                        tuple(17L, "REFUND", "환불", "Refund", "🔄", 4),
                        tuple(18L, "TAX_REFUND", "세금 환급", "Tax Refund", "🧾", 5),
                        tuple(19L, "TRANSFER", "이체", "Transfer", "💸", 6),
                        tuple(20L, "INVESTMENT", "투자 수익", "Investment", "📈", 7),
                        tuple(21L, "OTHER_INCOME", "기타", "Other", "📥", 8));
        assertThat(assets)
                .extracting(
                        Asset::getId,
                        Asset::getCode,
                        Asset::getDisplayNameKo,
                        Asset::getDisplayNameEn,
                        Asset::getSortOrder)
                .containsExactly(
                        tuple(1L, "CREDIT_CARD", "신용카드", "Credit Card", 1),
                        tuple(2L, "DEBIT_CARD", "체크카드", "Debit Card", 2),
                        tuple(3L, "CASH", "현금", "Cash", 3),
                        tuple(4L, "ACCOUNT", "계좌", "Account", 4),
                        tuple(5L, "CHECK", "수표", "Check", 5),
                        tuple(6L, "OTHER", "기타", "Other", 6));
    }

    @Test
    @DisplayName("카탈로그 테이블은 KO/EN 표시명을 갖고 owner 컬럼과 자산 icon 컬럼을 갖지 않는다")
    void catalog_schema_uses_shared_ko_en_names_without_owner_or_asset_icon() {
        assertThat(columnExists("category", "display_name_ko")).isTrue();
        assertThat(columnExists("category", "display_name_en")).isTrue();
        assertThat(columnExists("category", "display_name")).isFalse();
        assertThat(columnExists("category", "owner_member_id")).isFalse();
        assertThat(columnExists("asset", "display_name_ko")).isTrue();
        assertThat(columnExists("asset", "display_name_en")).isTrue();
        assertThat(columnExists("asset", "display_name")).isFalse();
        assertThat(columnExists("asset", "owner_member_id")).isFalse();
        assertThat(columnExists("asset", "icon")).isFalse();
    }

    @Test
    @DisplayName("ledger_entry는 클라이언트 거래 식별 컬럼과 회원별 부분 unique 인덱스를 갖는다")
    void ledger_entry_schema_has_client_entry_columns_and_partial_unique_index() {
        assertThat(columnExists("ledger_entry", "client_entry_id")).isTrue();
        assertThat(columnExists("ledger_entry", "client_payload_hash")).isTrue();
        assertThat(columnType("ledger_entry", "client_entry_id")).isEqualTo("uuid");
        assertThat(characterMaximumLength("ledger_entry", "client_payload_hash"))
                .isEqualTo(64);

        String indexDefinition = indexDefinition("uq_ledger_entry_member_client_entry");
        assertThat(indexDefinition).isNotNull();
        assertThat(indexDefinition.toLowerCase(Locale.ROOT))
                .contains("create unique index uq_ledger_entry_member_client_entry")
                .contains("(member_id, client_entry_id)")
                .contains("where (client_entry_id is not null)");
    }

    @Test
    @DisplayName("ledger_entry client_entry_id는 null 다중 저장을 허용하고 회원 내 비-null 중복을 차단한다")
    void ledger_entry_client_entry_id_unique_index_supports_idempotent_import() {
        UUID memberId = UUID.fromString("00000000-0000-0000-0000-000000000101");
        UUID otherMemberId = UUID.fromString("00000000-0000-0000-0000-000000000102");
        UUID clientEntryId = UUID.fromString("10000000-0000-0000-0000-000000000001");

        insertLedgerEntry(memberId, null, null);
        insertLedgerEntry(memberId, null, null);
        insertLedgerEntry(memberId, clientEntryId, "a".repeat(64));
        insertLedgerEntry(otherMemberId, clientEntryId, "a".repeat(64));

        assertThat(ledgerEntryCount(memberId)).isEqualTo(3);
        assertThatThrownBy(() -> insertLedgerEntry(memberId, clientEntryId, "b".repeat(64)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private boolean columnExists(String tableName, String columnName) {
        Integer count = jdbcTemplate.queryForObject(
                """
                select count(*)
                from information_schema.columns
                where table_schema = 'public'
                  and table_name = ?
                  and column_name = ?
                """,
                Integer.class,
                tableName,
                columnName);
        return count != null && count == 1;
    }

    private String columnType(String tableName, String columnName) {
        return jdbcTemplate.queryForObject(
                """
                select data_type
                from information_schema.columns
                where table_schema = 'public'
                  and table_name = ?
                  and column_name = ?
                """,
                String.class,
                tableName,
                columnName);
    }

    private Integer characterMaximumLength(String tableName, String columnName) {
        return jdbcTemplate.queryForObject(
                """
                select character_maximum_length
                from information_schema.columns
                where table_schema = 'public'
                  and table_name = ?
                  and column_name = ?
                """,
                Integer.class,
                tableName,
                columnName);
    }

    private String indexDefinition(String indexName) {
        return jdbcTemplate.queryForObject(
                """
                select indexdef
                from pg_indexes
                where schemaname = 'public'
                  and tablename = 'ledger_entry'
                  and indexname = ?
                """,
                String.class,
                indexName);
    }

    private void insertLedgerEntry(UUID memberId, UUID clientEntryId, String clientPayloadHash) {
        jdbcTemplate.update(
                """
                insert into ledger_entry (
                    member_id,
                    transaction_type,
                    category_id,
                    asset_id,
                    original_amount,
                    currency_code,
                    applied_rate,
                    rate_base_date,
                    krw_amount,
                    transaction_date,
                    client_entry_id,
                    client_payload_hash
                )
                values (
                    cast(? as uuid),
                    'EXPENSE',
                    1,
                    1,
                    1000.00,
                    'KRW',
                    1.000000,
                    '2026-06-01',
                    1000.00,
                    '2026-06-01',
                    cast(? as uuid),
                    ?
                )
                """,
                memberId.toString(),
                clientEntryId == null ? null : clientEntryId.toString(),
                clientPayloadHash);
    }

    private Integer ledgerEntryCount(UUID memberId) {
        return jdbcTemplate.queryForObject(
                """
                select count(*)
                from ledger_entry
                where member_id = cast(? as uuid)
                """,
                Integer.class,
                memberId.toString());
    }
}

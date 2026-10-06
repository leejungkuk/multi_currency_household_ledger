package com.self.multi_currency_household_ledger.ledger.domain;

import com.self.multi_currency_household_ledger.common.entity.BaseEntity;
import com.self.multi_currency_household_ledger.common.exception.BusinessException;
import com.self.multi_currency_household_ledger.exchange.domain.CurrencyCode;
import com.self.multi_currency_household_ledger.ledger.exception.BudgetErrorCode;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@Table(name = "budget")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Budget extends BaseEntity {

    private static final BigDecimal MAX_AMOUNT = new BigDecimal("99999999");

    /** 카테고리 몫의 줄 순서. 동률은 카테고리 id 로 깬다 — 로딩 경로와 무관하게 결정적이다. */
    private static final Comparator<Map.Entry<Long, CategoryAllocation>> LINE_ORDER = Comparator.comparingInt(
                    (Map.Entry<Long, CategoryAllocation> entry) ->
                            entry.getValue().sortOrder())
            .thenComparing(Map.Entry::getKey);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private UUID memberId;

    /** 그 달의 1일. */
    @Column(nullable = false)
    private LocalDate month;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 3)
    private CurrencyCode currencyCode;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal totalAmount;

    /** 결제수단 몫. null 이면 그 그룹 몫이 없다. */
    @Column(precision = 19, scale = 2)
    private BigDecimal creditCardAmount;

    @Column(precision = 19, scale = 2)
    private BigDecimal cashAndDebitAmount;

    @Column(precision = 19, scale = 2)
    private BigDecimal accountAndOtherAmount;

    /** 카테고리 몫(카테고리 id → 금액·줄 순서). */
    @ElementCollection
    @CollectionTable(name = "budget_category_allocation", joinColumns = @JoinColumn(name = "budget_id"))
    @MapKeyColumn(name = "category_id")
    private Map<Long, CategoryAllocation> categoryAmounts = new HashMap<>();

    /** 새 행은 flush 전에 반드시 {@link #replaceAmounts} 로 값을 채운다 — 통화·금액 컬럼이 not null 이다. */
    public Budget(UUID memberId, YearMonth month) {
        this.memberId = memberId;
        this.month = month.atDay(1);
    }

    /** 카테고리 몫은 줄 순서(sortOrder, 카테고리 id)대로 담는다 — Hibernate 컬렉션의 로딩 순서에 기대지 않는다. */
    public BudgetAmounts amounts() {
        Map<PaymentGroup, BigDecimal> groups = new EnumMap<>(PaymentGroup.class);
        for (PaymentGroup group : PaymentGroup.values()) {
            BigDecimal amount = groupAmount(group, UnaryOperator.identity());
            if (amount != null) {
                groups.put(group, amount);
            }
        }
        Map<Long, BigDecimal> categories = new LinkedHashMap<>();
        categoryAmounts.entrySet().stream()
                .sorted(LINE_ORDER)
                .forEach(
                        entry -> categories.put(entry.getKey(), entry.getValue().amount()));
        return new BudgetAmounts(
                currencyCode,
                totalAmount,
                Collections.unmodifiableMap(groups),
                Collections.unmodifiableMap(categories));
    }

    /**
     * 이 행을 {@code memberId} 회원의 같은 달 새 행으로 복사한다({@code this} 는 바뀌지 않는다). 카테고리 몫은 줄 순서대로 돌며 {@code lineTargets}
     * 의 키인 줄만 그 값(회원 쪽 카테고리)으로 옮기고, 아닌 줄은 뺀다. 남은 줄의 상대 순서는 지키고 줄 순서는 0 부터 다시 매긴다. 월 범위·삭제 카테고리는
     * 검증하지 않는다 — 이미 저장 검증을 통과한 행의 복사다.
     */
    public Budget copyTo(UUID memberId, Map<Long, Category> lineTargets) {
        lineTargets.values().forEach(target -> requireAllocatable(List.of(target)));
        List<GroupAmount> groups = new ArrayList<>();
        for (PaymentGroup group : PaymentGroup.values()) {
            BigDecimal amount = groupAmount(group, UnaryOperator.identity());
            if (amount != null) {
                groups.add(new GroupAmount(group, amount));
            }
        }
        List<CategoryAmount> lines = categoryAmounts.entrySet().stream()
                .sorted(LINE_ORDER)
                .filter(entry -> lineTargets.containsKey(entry.getKey()))
                .map(entry -> new CategoryAmount(
                        lineTargets.get(entry.getKey()).getId(),
                        entry.getValue().amount()))
                .toList();
        Budget copy = new Budget(memberId, YearMonth.from(month));
        copy.replaceAmounts(currencyCode, totalAmount, groups, lines);
        return copy;
    }

    /** 카테고리 몫의 카테고리 id 들. */
    public Set<Long> allocatedCategoryIds() {
        return Set.copyOf(categoryAmounts.keySet());
    }

    /**
     * 금액 세트를 통째로 교체한다. 요청에 없는 결제수단 그룹은 null 이 된다. 카테고리 몫은 같은 Map 에서 키별로 갱신한다 — 빠진 키는 지우고, 있던 키는
     * 금액·줄 순서만 바꾸고, 새 키만 더한다. Map 을 갈아 끼우면 Hibernate 가 그 예산의 몫 행을 전부 지우고 다시 넣는다. 줄 순서는
     * {@code categoryAmounts} 의 인덱스(0부터)다. 검증을 모두 통과해야 상태가 바뀐다.
     */
    public void replaceAmounts(
            CurrencyCode currency,
            BigDecimal total,
            List<GroupAmount> groupAmounts,
            List<CategoryAmount> categoryAmounts) {
        if (total == null) {
            throw new BusinessException(BudgetErrorCode.BUDGET_TOTAL_REQUIRED);
        }
        requireValidAmount(total, currency);
        Map<PaymentGroup, BigDecimal> groups =
                toUniqueMap(groupAmounts, GroupAmount::paymentGroup, GroupAmount::amount, currency);
        Map<Long, BigDecimal> categories =
                toUniqueMap(categoryAmounts, CategoryAmount::categoryId, CategoryAmount::amount, currency);
        requireWithinTotal(groups.values(), total);
        requireWithinTotal(categories.values(), total);

        this.currencyCode = currency;
        this.totalAmount = total;
        for (PaymentGroup group : PaymentGroup.values()) {
            groupAmount(group, ignored -> groups.get(group));
        }
        this.categoryAmounts.keySet().retainAll(categories.keySet());
        // toUniqueMap 은 요청 순서를 지키므로 순회 순서가 곧 요청 인덱스다.
        int sortOrder = 0;
        for (Map.Entry<Long, BigDecimal> category : categories.entrySet()) {
            this.categoryAmounts.put(category.getKey(), new CategoryAllocation(category.getValue(), sortOrder++));
        }
        // 카테고리 몫만 바뀌면 이 행은 더럽혀지지 않는다 — 익명 정리의 활동 술어가 budget.updated_at 을 보므로 저장마다 갱신한다.
        markModified();
    }

    /** 몫 카테고리는 지출 카테고리여야 한다. */
    public void requireAllocatable(Collection<Category> categories) {
        for (Category category : categories) {
            if (category.getTransactionType() != TransactionType.EXPENSE) {
                throw new BusinessException(BudgetErrorCode.BUDGET_INVALID_ALLOCATION);
            }
        }
    }

    /**
     * 그룹의 몫 컬럼을 {@code change} 로 바꾸고 그 값을 돌려준다(읽기만 할 때는 identity). PaymentGroup ↔ 컬럼 매핑은 여기 한 곳이다 — default 가
     * 없어 그룹이 늘면 컴파일이 깨진다.
     */
    private BigDecimal groupAmount(PaymentGroup group, UnaryOperator<BigDecimal> change) {
        return switch (group) {
            case CREDIT_CARD -> creditCardAmount = change.apply(creditCardAmount);
            case CASH_AND_DEBIT -> cashAndDebitAmount = change.apply(cashAndDebitAmount);
            case ACCOUNT_AND_OTHER -> accountAndOtherAmount = change.apply(accountAndOtherAmount);
        };
    }

    private static <T, K> Map<K, BigDecimal> toUniqueMap(
            List<T> items, Function<T, K> key, Function<T, BigDecimal> amount, CurrencyCode currency) {
        Map<K, BigDecimal> map = new LinkedHashMap<>();
        for (T item : items) {
            BigDecimal value = amount.apply(item);
            requireValidAmount(value, currency);
            if (map.put(key.apply(item), value) != null) {
                throw new BusinessException(BudgetErrorCode.BUDGET_INVALID_ALLOCATION);
            }
        }
        return map;
    }

    /** 몫 합은 전체 이하여야 한다(같으면 통과). compareTo 라 스케일 차이는 무시한다. */
    private static void requireWithinTotal(Collection<BigDecimal> shares, BigDecimal total) {
        if (shares.stream().reduce(BigDecimal.ZERO, BigDecimal::add).compareTo(total) > 0) {
            throw new BusinessException(BudgetErrorCode.BUDGET_ALLOCATION_EXCEEDS_TOTAL);
        }
    }

    private static void requireValidAmount(BigDecimal amount, CurrencyCode currency) {
        if (amount == null
                || amount.signum() < 0
                || amount.compareTo(MAX_AMOUNT) > 0
                || amount.stripTrailingZeros().scale() > currency.fractionDigits()) {
            throw new BusinessException(BudgetErrorCode.BUDGET_INVALID_AMOUNT);
        }
    }

    public record GroupAmount(PaymentGroup paymentGroup, BigDecimal amount) {}

    public record CategoryAmount(Long categoryId, BigDecimal amount) {}

    /** 카테고리 몫 한 줄의 값. Hibernate 는 컬럼별로 비교하므로 금액·순서가 같은 줄은 UPDATE 하지 않는다. */
    @Embeddable
    public record CategoryAllocation(
            @Column(name = "amount", nullable = false, precision = 19, scale = 2) BigDecimal amount,
            @Column(name = "sort_order", nullable = false) int sortOrder) {}
}

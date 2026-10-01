package com.self.multi_currency_household_ledger.ledger.domain;

import com.self.multi_currency_household_ledger.common.entity.BaseEntity;
import com.self.multi_currency_household_ledger.common.exception.BusinessException;
import com.self.multi_currency_household_ledger.exchange.domain.CurrencyCode;
import com.self.multi_currency_household_ledger.ledger.exception.BudgetErrorCode;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@Table(name = "budget")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Budget extends BaseEntity {

    private static final BigDecimal MAX_AMOUNT = new BigDecimal("99999999");

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

    @OneToMany(mappedBy = "budget", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<BudgetAllocation> allocations = new ArrayList<>();

    /** 새 행은 flush 전에 반드시 {@link #replaceAmounts} 로 값을 채운다 — 통화·금액 컬럼이 not null 이다. */
    public Budget(UUID memberId, YearMonth month) {
        this.memberId = memberId;
        this.month = month.atDay(1);
    }

    public BudgetAmounts amounts() {
        Map<PaymentGroup, BigDecimal> groups = new EnumMap<>(PaymentGroup.class);
        Map<Long, BigDecimal> categories = new LinkedHashMap<>();
        for (BudgetAllocation allocation : allocations) {
            if (allocation.getPaymentGroup() != null) {
                groups.put(allocation.getPaymentGroup(), allocation.getAmount());
            } else {
                categories.put(allocation.getCategoryId(), allocation.getAmount());
            }
        }
        return new BudgetAmounts(
                currencyCode,
                totalAmount,
                Collections.unmodifiableMap(groups),
                Collections.unmodifiableMap(categories));
    }

    /** 카테고리 몫의 카테고리 id 들. */
    public Set<Long> allocatedCategoryIds() {
        return allocations.stream()
                .map(BudgetAllocation::getCategoryId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    /**
     * 금액 세트를 통째로 교체한다. 몫은 키별 제자리 갱신이다 — 있던 키는 금액만 바꾸고, 빠진 키는 지우고, 새 키만 더한다. 전부 지우고 새로 넣으면
     * Hibernate 가 insert 를 orphan delete 보다 먼저 flush 해 unique 위반이 난다. 검증을 모두 통과해야 상태가 바뀐다.
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

        this.currencyCode = currency;
        this.totalAmount = total;
        allocations.removeIf(allocation -> allocation.getPaymentGroup() != null
                ? !groups.containsKey(allocation.getPaymentGroup())
                : !categories.containsKey(allocation.getCategoryId()));
        for (BudgetAllocation allocation : allocations) {
            BigDecimal amount = allocation.getPaymentGroup() != null
                    ? groups.remove(allocation.getPaymentGroup())
                    : categories.remove(allocation.getCategoryId());
            allocation.changeAmount(amount);
        }
        groups.forEach((group, amount) -> attach(BudgetAllocation.forPaymentGroup(group, amount)));
        categories.forEach((categoryId, amount) -> attach(BudgetAllocation.forCategory(categoryId, amount)));
        // 몫만 바뀌면 이 행은 더럽혀지지 않는다 — 익명 정리의 활동 술어가 budget.updated_at 을 보므로 저장마다 갱신한다.
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

    private void attach(BudgetAllocation allocation) {
        allocation.attachTo(this);
        allocations.add(allocation);
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
}

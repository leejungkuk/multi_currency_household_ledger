package com.self.multi_currency_household_ledger.ledger.service;

import com.self.multi_currency_household_ledger.common.exception.BusinessException;
import com.self.multi_currency_household_ledger.exchange.domain.CurrencyCode;
import com.self.multi_currency_household_ledger.exchange.domain.TtsTimeline;
import com.self.multi_currency_household_ledger.exchange.service.ExchangeRateService;
import com.self.multi_currency_household_ledger.ledger.domain.Budget;
import com.self.multi_currency_household_ledger.ledger.domain.BudgetApplyTo;
import com.self.multi_currency_household_ledger.ledger.domain.BudgetEvaluation;
import com.self.multi_currency_household_ledger.ledger.domain.BudgetKind;
import com.self.multi_currency_household_ledger.ledger.domain.BudgetMonthPolicy;
import com.self.multi_currency_household_ledger.ledger.domain.BudgetRepository;
import com.self.multi_currency_household_ledger.ledger.domain.BudgetResolution;
import com.self.multi_currency_household_ledger.ledger.domain.Category;
import com.self.multi_currency_household_ledger.ledger.domain.CategoryRepository;
import com.self.multi_currency_household_ledger.ledger.domain.LedgerEntryRepository;
import com.self.multi_currency_household_ledger.ledger.domain.TransactionType;
import com.self.multi_currency_household_ledger.ledger.dto.BudgetAxisResponse;
import com.self.multi_currency_household_ledger.ledger.dto.MonthlyBudgetResponse;
import com.self.multi_currency_household_ledger.ledger.dto.SaveBudgetRequest;
import com.self.multi_currency_household_ledger.ledger.exception.LedgerErrorCode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 월 예산의 트랜잭션·흐름. 규칙은 도메인(Budget·BudgetMonthPolicy·BudgetResolution·BudgetEvaluation)에 있다.
 *
 * <p>BudgetRepository 의 월 인자(LocalDate)에는 {@code YearMonth.atDay(1)} 만 넘긴다 — 1일이 아닌 날짜는 MONTH 행을 조용히 놓친다.
 */
@Service
public class BudgetService {

    private final BudgetRepository budgetRepository;
    private final CategoryRepository categoryRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final ExchangeRateService exchangeRateService;
    private final Clock clock;
    private final BudgetMonthPolicy monthPolicy;

    public BudgetService(
            BudgetRepository budgetRepository,
            CategoryRepository categoryRepository,
            LedgerEntryRepository ledgerEntryRepository,
            ExchangeRateService exchangeRateService,
            Clock clock) {
        this.budgetRepository = budgetRepository;
        this.categoryRepository = categoryRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.exchangeRateService = exchangeRateService;
        this.clock = clock;
        this.monthPolicy = new BudgetMonthPolicy(clock);
    }

    @Transactional(readOnly = true)
    public MonthlyBudgetResponse getMonthlyBudget(UUID memberId, YearMonth month) {
        return read(memberId, month);
    }

    /**
     * 한 축의 금액 세트(amounts 가 null 이면 끔)를 저장하고 그 달 전체를 돌려준다. 회원이 이미 삭제됐으면 insert 가 fk_budget_member 로 실패하고,
     * 그 DataIntegrityViolation 은 잡지 않고 그대로 올려 GlobalExceptionHandler 가 401 로 매핑한다.
     */
    @Transactional
    public MonthlyBudgetResponse save(
            UUID memberId,
            TransactionType axis,
            YearMonth month,
            BudgetApplyTo applyTo,
            SaveBudgetRequest.BudgetAmountsRequest amounts) {
        monthPolicy.requireWritable(month);
        budgetRepository.lockMember(memberId);
        // 락을 잡은 뒤에 읽어야 동시 카테고리 삭제의 커밋 결과를 본다(DESIGN §2).
        List<Category> categories = findUsableCategories(memberId, amounts);

        LocalDate first = month.atDay(1);
        if (applyTo == BudgetApplyTo.FROM_THIS_MONTH) {
            budgetRepository.deleteDefaultsAfter(memberId, axis, first);
            budgetRepository
                    .findByMemberIdAndAxisAndKindAndMonth(memberId, axis, BudgetKind.MONTH, first)
                    .ifPresent(budgetRepository::delete);
        }
        Budget budget = budgetRepository
                .findByMemberIdAndAxisAndKindAndMonth(memberId, axis, applyTo.getKind(), first)
                .orElseGet(() -> new Budget(memberId, axis, applyTo.getKind(), month, null, null, List.of()));
        if (amounts == null) {
            budget.turnOff();
        } else {
            budget.requireAllocatable(categories);
            budget.replaceAmounts(
                    amounts.currency(), amounts.totalAmount(), amounts.groupAmounts(), amounts.categoryAmountList());
        }
        budgetRepository.save(budget);
        // 제약 위반을 커밋이 아니라 여기서 드러내 GlobalExceptionHandler 가 받게 한다.
        budgetRepository.flush();
        return read(memberId, month);
    }

    /** 그 달의 달별 값을 지워 기본값으로 되돌린다. 없으면 아무것도 지우지 않는다. */
    @Transactional
    public MonthlyBudgetResponse revert(UUID memberId, TransactionType axis, YearMonth month) {
        monthPolicy.requireWritable(month);
        budgetRepository.lockMember(memberId);
        budgetRepository
                .findByMemberIdAndAxisAndKindAndMonth(memberId, axis, BudgetKind.MONTH, month.atDay(1))
                .ifPresent(budgetRepository::delete);
        budgetRepository.flush();
        return read(memberId, month);
    }

    /** 회원 예산 advisory lock — 트랜잭션이 끝날 때 풀리고, 같은 트랜잭션에서 다시 잡아도 막히지 않는다(재진입). */
    @Transactional
    public void lockMember(UUID memberId) {
        budgetRepository.lockMember(memberId);
    }

    /**
     * 카테고리 삭제의 예산 쪽 — 호출자(CatalogService)의 트랜잭션에서 비활성화 flush 뒤에 돈다. 이번 달 C 부터의 몫만 떼고, C 에 적용되던 옛 DEFAULT 는
     * 고치지 않고 그 카테고리를 뺀 (DEFAULT, C) 로 갈라낸다. C 이전 행은 건드리지 않는다 — 지난 달 해석이 바뀐다(요구사항 §5).
     */
    @Transactional
    public void detachCategory(UUID memberId, Long categoryId) {
        budgetRepository.lockMember(memberId);
        YearMonth current = monthPolicy.current();
        LocalDate first = current.atDay(1);
        budgetRepository.deleteCategoryAllocationsFrom(memberId, categoryId, first);
        for (TransactionType axis : TransactionType.values()) {
            budgetRepository
                    .findFirstByMemberIdAndAxisAndKindAndMonthLessThanOrderByMonthDesc(
                            memberId, axis, BudgetKind.DEFAULT, first)
                    .filter(applied -> applied.allocatesCategory(categoryId))
                    .filter(applied -> budgetRepository
                            .findByMemberIdAndAxisAndKindAndMonth(memberId, axis, BudgetKind.DEFAULT, first)
                            .isEmpty())
                    .ifPresent(applied -> budgetRepository.save(applied.defaultWithoutCategory(current, categoryId)));
        }
    }

    private List<Category> findUsableCategories(UUID memberId, SaveBudgetRequest.BudgetAmountsRequest amounts) {
        if (amounts == null) {
            return List.of();
        }
        Set<Long> ids = amounts.categoryIds();
        if (ids.isEmpty()) {
            return List.of();
        }
        // 없는·삭제된·다른 회원의 카테고리는 모두 빠진다 — IDOR 이 막히는 지점이다.
        List<Category> categories = categoryRepository.findUsableByIds(memberId, ids);
        if (categories.size() < ids.size()) {
            throw new BusinessException(LedgerErrorCode.CATEGORY_NOT_FOUND);
        }
        return categories;
    }

    private MonthlyBudgetResponse read(UUID memberId, YearMonth month) {
        List<Budget> rows = budgetRepository.findForMonth(memberId, month.atDay(1));
        BudgetResolution expense = BudgetResolution.resolve(rowsOf(rows, TransactionType.EXPENSE), month);
        BudgetResolution income = BudgetResolution.resolve(rowsOf(rows, TransactionType.INCOME), month);

        Set<Long> categoryIds = new HashSet<>();
        for (BudgetResolution resolution : List.of(expense, income)) {
            if (resolution.amounts() != null) {
                categoryIds.addAll(resolution.amounts().categoryAmounts().keySet());
            }
        }
        List<Category> categories =
                categoryIds.isEmpty() ? List.of() : categoryRepository.findVisibleByIds(memberId, categoryIds);

        // 시계는 한 번만 읽는다 — current·editable·remainingDays 가 자정 월 경계에서 서로 다른 날을 보지 않게.
        BudgetMonthPolicy now = new BudgetMonthPolicy(Clock.fixed(clock.instant(), clock.getZone()));
        Integer remainingDays = now.remainingDaysIncludingToday(month);
        Map<CurrencyCode, TtsTimeline> timelines = new EnumMap<>(CurrencyCode.class);
        return MonthlyBudgetResponse.of(
                month,
                now.current(),
                now.isEditable(month),
                remainingDays,
                budgetRepository.existsByMemberIdAndTotalAmountIsNotNull(memberId),
                axisResponse(memberId, TransactionType.EXPENSE, expense, month, remainingDays, timelines, categories),
                axisResponse(memberId, TransactionType.INCOME, income, month, remainingDays, timelines, categories));
    }

    private BudgetAxisResponse axisResponse(
            UUID memberId,
            TransactionType axis,
            BudgetResolution resolution,
            YearMonth month,
            Integer remainingDays,
            Map<CurrencyCode, TtsTimeline> timelines,
            List<Category> categories) {
        if (resolution.amounts() == null) {
            return BudgetAxisResponse.withoutAmounts(resolution.source());
        }
        CurrencyCode currency = resolution.amounts().currency();
        // 외화 예산은 반드시 타임라인을 넘긴다(도메인은 null 을 받지 않는다). 두 축의 통화가 같으면 한 번만 읽는다.
        TtsTimeline timeline = currency.isBase()
                ? null
                : timelines.computeIfAbsent(
                        currency, c -> exchangeRateService.getTtsTimeline(c, month.atDay(1), month.atEndOfMonth()));
        BudgetEvaluation evaluation = BudgetEvaluation.evaluate(
                axis,
                resolution.amounts(),
                ledgerEntryRepository.findBudgetTransactions(
                        memberId, axis, month.atDay(1), month.plusMonths(1).atDay(1)),
                timeline,
                remainingDays);
        return BudgetAxisResponse.of(resolution, evaluation, categories);
    }

    private static List<Budget> rowsOf(List<Budget> rows, TransactionType axis) {
        return rows.stream().filter(row -> row.getAxis() == axis).toList();
    }
}

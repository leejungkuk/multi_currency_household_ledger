package com.self.multi_currency_household_ledger.ledger.service;

import com.self.multi_currency_household_ledger.common.exception.BusinessException;
import com.self.multi_currency_household_ledger.exchange.domain.TtsTimeline;
import com.self.multi_currency_household_ledger.exchange.service.ExchangeRateService;
import com.self.multi_currency_household_ledger.ledger.domain.Budget;
import com.self.multi_currency_household_ledger.ledger.domain.BudgetAmounts;
import com.self.multi_currency_household_ledger.ledger.domain.BudgetEvaluation;
import com.self.multi_currency_household_ledger.ledger.domain.BudgetMonthPolicy;
import com.self.multi_currency_household_ledger.ledger.domain.BudgetRepository;
import com.self.multi_currency_household_ledger.ledger.domain.Category;
import com.self.multi_currency_household_ledger.ledger.domain.CategoryRepository;
import com.self.multi_currency_household_ledger.ledger.domain.LedgerEntryRepository;
import com.self.multi_currency_household_ledger.ledger.domain.TransactionType;
import com.self.multi_currency_household_ledger.ledger.dto.MonthlyBudgetResponse;
import com.self.multi_currency_household_ledger.ledger.dto.SaveBudgetRequest;
import com.self.multi_currency_household_ledger.ledger.exception.LedgerErrorCode;
import java.time.Clock;
import java.time.YearMonth;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 월 예산의 트랜잭션·흐름. 규칙은 도메인(Budget·BudgetMonthPolicy·BudgetEvaluation)에 있다.
 *
 * <p>BudgetRepository 의 월 인자(LocalDate)에는 {@code YearMonth.atDay(1)} 만 넘긴다 — 1일이 아닌 날짜는 행을 조용히 놓친다.
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
     * 그 달의 금액 세트를 통째로 교체하고 그 달 전체를 돌려준다. 회원이 이미 삭제됐으면 insert 가 fk_budget_member 로 실패하고, 그
     * DataIntegrityViolation 은 잡지 않고 그대로 올려 GlobalExceptionHandler 가 401 로 매핑한다.
     */
    @Transactional
    public MonthlyBudgetResponse save(UUID memberId, YearMonth month, SaveBudgetRequest request) {
        monthPolicy.requireWritable(month);
        budgetRepository.lockMember(memberId);
        // 락을 잡은 뒤에 읽어야 같은 회원의 동시 쓰기 커밋 결과를 본다(DESIGN §2).
        Optional<Budget> existing = budgetRepository.findByMemberIdAndMonth(memberId, month.atDay(1));
        // 그 달 행에 이미 몫이 있는 카테고리는 삭제됐어도 받는다 — 새로 넣는 id 만 활성·소유를 확인한다(DESIGN §4).
        Set<Long> newCategoryIds = new HashSet<>(request.categoryIds());
        existing.ifPresent(budget -> newCategoryIds.removeAll(budget.allocatedCategoryIds()));
        List<Category> newCategories = findUsableCategories(memberId, newCategoryIds);

        Budget budget = existing.orElseGet(() -> new Budget(memberId, month));
        budget.requireAllocatable(newCategories);
        budget.replaceAmounts(
                request.currency(), request.totalAmount(), request.groupAmounts(), request.categoryAmountList());
        budgetRepository.save(budget);
        // 제약 위반을 커밋이 아니라 여기서 드러내 GlobalExceptionHandler 가 받게 한다.
        budgetRepository.flush();
        return read(memberId, month);
    }

    /** 그 달의 예산을 지워 미설정으로 만든다. 없으면 아무것도 지우지 않는다. */
    @Transactional
    public MonthlyBudgetResponse delete(UUID memberId, YearMonth month) {
        monthPolicy.requireWritable(month);
        budgetRepository.lockMember(memberId);
        budgetRepository.findByMemberIdAndMonth(memberId, month.atDay(1)).ifPresent(budgetRepository::delete);
        budgetRepository.flush();
        return read(memberId, month);
    }

    private List<Category> findUsableCategories(UUID memberId, Set<Long> ids) {
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
        // 시계는 한 번만 읽는다 — current·remainingDays 가 자정 월 경계에서 서로 다른 날을 보지 않게.
        BudgetMonthPolicy now = new BudgetMonthPolicy(Clock.fixed(clock.instant(), clock.getZone()));
        YearMonth current = now.current();
        Integer remainingDays = now.remainingDaysIncludingToday(month);
        Optional<Budget> budget = budgetRepository.findByMemberIdAndMonth(memberId, month.atDay(1));
        if (budget.isEmpty()) {
            return MonthlyBudgetResponse.notSet(
                    month, current, remainingDays, budgetRepository.existsByMemberId(memberId));
        }

        BudgetAmounts amounts = budget.get().amounts();
        Set<Long> categoryIds = amounts.categoryAmounts().keySet();
        List<Category> categories =
                categoryIds.isEmpty() ? List.of() : categoryRepository.findVisibleByIds(memberId, categoryIds);
        // 외화 예산은 반드시 타임라인을 넘긴다(도메인은 null 을 받지 않는다).
        TtsTimeline timeline = amounts.currency().isBase()
                ? null
                : exchangeRateService.getTtsTimeline(amounts.currency(), month.atDay(1), month.atEndOfMonth());
        BudgetEvaluation evaluation = BudgetEvaluation.evaluate(
                amounts,
                ledgerEntryRepository.findBudgetTransactions(
                        memberId,
                        TransactionType.EXPENSE,
                        month.atDay(1),
                        month.plusMonths(1).atDay(1)),
                timeline,
                remainingDays);
        return MonthlyBudgetResponse.of(month, current, remainingDays, amounts.currency(), evaluation, categories);
    }
}

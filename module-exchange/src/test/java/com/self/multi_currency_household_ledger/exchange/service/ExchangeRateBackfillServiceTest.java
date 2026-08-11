package com.self.multi_currency_household_ledger.exchange.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;

import com.self.multi_currency_household_ledger.common.exception.BusinessException;
import com.self.multi_currency_household_ledger.exchange.domain.ExchangeRateRepository;
import com.self.multi_currency_household_ledger.exchange.exception.ExchangeErrorCode;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ExchangeRateBackfillServiceTest {

    @Mock
    private ExchangeRateRepository exchangeRateRepository;

    @Mock
    private ExchangeRateService exchangeRateService;

    private ExchangeRateBackfillService backfillService;

    @BeforeEach
    void setUp() {
        backfillService = new ExchangeRateBackfillService(exchangeRateRepository, exchangeRateService);
    }

    @Test
    @DisplayName("종료일을 먼저 수집하고 과거 결손일의 쿼터 오류에서 중단한다")
    void prioritizes_end_date_before_past_quota_abort() {
        LocalDate past = LocalDate.of(2026, 8, 7);
        LocalDate end = LocalDate.of(2026, 8, 10);
        given(exchangeRateRepository.findCompleteBaseDates(eq(past), eq(end), anyCollection(), anyLong()))
                .willReturn(List.of());
        given(exchangeRateRepository.findCompleteBaseDates(eq(end), eq(end), anyCollection(), anyLong()))
                .willReturn(List.of(end));
        given(exchangeRateService.fetchAndSaveRates(end)).willReturn(12);
        given(exchangeRateService.fetchAndSaveRates(past))
                .willThrow(new BusinessException(ExchangeErrorCode.EXCHANGE_API_LIMIT_EXCEEDED));

        var result = backfillService.backfill(past, end);

        assertThat(result.status()).isEqualTo(ExchangeRateBackfillService.BackfillStatus.QUOTA_ABORTED);
        assertThat(result.filledDays()).isEqualTo(1);
        assertThat(result.endDateComplete()).isTrue();
        InOrder order = inOrder(exchangeRateService);
        order.verify(exchangeRateService).fetchAndSaveRates(end);
        order.verify(exchangeRateService).fetchAndSaveRates(past);
    }
}

package com.self.multi_currency_household_ledger.exchange.controller;

import com.self.multi_currency_household_ledger.common.dto.ApiResponse;
import com.self.multi_currency_household_ledger.common.exception.BusinessException;
import com.self.multi_currency_household_ledger.exchange.domain.ExchangeRate;
import com.self.multi_currency_household_ledger.exchange.dto.ExchangeRateResponse;
import com.self.multi_currency_household_ledger.exchange.exception.ExchangeErrorCode;
import com.self.multi_currency_household_ledger.exchange.service.ExchangeRateService;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Profile("local")
@RestController
@RequestMapping("/api/v1/exchange-rates")
@RequiredArgsConstructor
public class ManualRateCollectController {

    private final ExchangeRateService exchangeRateService;
    private final Clock clock;

    @PostMapping("/collect")
    public ApiResponse<List<ExchangeRateResponse>> collect(
            @RequestParam(value = "date", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate date) {
        LocalDate target = date != null ? date : LocalDate.now(clock);
        ExchangeRate.assertNotFuture(target, clock);

        boolean fetched = exchangeRateService.fetchAndSaveRates(target);
        if (!fetched) {
            throw new BusinessException(ExchangeErrorCode.EXCHANGE_API_ERROR);
        }

        List<ExchangeRateResponse> responses = exchangeRateService.getAllRatesByDate(target).stream()
                .map(rate -> ExchangeRateResponse.from(rate, target))
                .toList();
        return ApiResponse.success(responses);
    }
}

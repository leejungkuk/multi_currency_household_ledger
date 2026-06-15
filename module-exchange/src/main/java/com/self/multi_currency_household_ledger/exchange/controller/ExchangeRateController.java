package com.self.multi_currency_household_ledger.exchange.controller;

import com.self.multi_currency_household_ledger.common.dto.ApiResponse;
import com.self.multi_currency_household_ledger.exchange.domain.CurrencyCode;
import com.self.multi_currency_household_ledger.exchange.domain.ExchangeRate;
import com.self.multi_currency_household_ledger.exchange.dto.ExchangeRateResponse;
import com.self.multi_currency_household_ledger.exchange.dto.ExchangeRateStatusResponse;
import com.self.multi_currency_household_ledger.exchange.service.ExchangeRateService;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/exchange-rates")
@RequiredArgsConstructor
public class ExchangeRateController {

    private final ExchangeRateService exchangeRateService;
    private final Clock clock;

    @GetMapping
    public ApiResponse<List<ExchangeRateResponse>> getRatesByDate(
            @RequestParam("date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        List<ExchangeRateResponse> responses = exchangeRateService.getAllRatesByDate(date).stream()
                .map(rate -> ExchangeRateResponse.from(rate, date))
                .toList();
        return ApiResponse.success(responses);
    }

    /** date 지정 시 해당일(없으면 직전 영업일 fallback), 생략 시 최신 — stale 판정 기준일은 생략 시 KST 오늘. */
    @GetMapping("/{currencyCode}")
    public ApiResponse<ExchangeRateResponse> getRate(
            @PathVariable("currencyCode") CurrencyCode currencyCode,
            @RequestParam(value = "date", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate date) {
        ExchangeRate rate = date != null
                ? exchangeRateService.getRateOnOrBefore(currencyCode, date)
                : exchangeRateService.getLatestRate(currencyCode);
        LocalDate effectiveDate = date != null ? date : LocalDate.now(clock);
        return ApiResponse.success(ExchangeRateResponse.from(rate, effectiveDate));
    }

    @GetMapping("/status")
    public ApiResponse<ExchangeRateStatusResponse> getStatus() {
        return ApiResponse.success(ExchangeRateStatusResponse.from(exchangeRateService.getLatestRatesByCurrency()));
    }

    /** 내부 운영/스케줄러용 환율 수집 트리거. 사용자 설정 화면 계약에서는 사용하지 않는다. */
    @PostMapping("/fetch")
    public ApiResponse<Void> fetchRatesForScheduler(
            @RequestParam("date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        exchangeRateService.fetchAndSaveRates(date);
        return ApiResponse.success(null);
    }
}

package com.self.multi_currency_household_ledger.exchange.controller;

import com.self.multi_currency_household_ledger.common.dto.ApiResponse;
import com.self.multi_currency_household_ledger.common.web.CacheControlHeaders;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
    public ResponseEntity<ApiResponse<List<ExchangeRateResponse>>> getRatesByDate(
            @RequestParam("date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        List<ExchangeRateResponse> responses = exchangeRateService.getAllRatesByDate(date).stream()
                .map(rate -> ExchangeRateResponse.from(rate, date))
                .toList();
        return publicRead(responses);
    }

    @GetMapping("/snapshot")
    public ResponseEntity<ApiResponse<List<ExchangeRateResponse>>> getSnapshot(
            @RequestParam(value = "date", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate date) {
        LocalDate effectiveDate = date != null ? date : LocalDate.now(clock);
        List<ExchangeRateResponse> responses = exchangeRateService.getSnapshot(effectiveDate).stream()
                .map(rate -> ExchangeRateResponse.from(rate, effectiveDate))
                .toList();
        return publicRead(responses);
    }

    /** date 지정 시 해당일(없으면 직전 영업일 fallback), 생략 시 최신 — stale 판정 기준일은 생략 시 KST 오늘. */
    @GetMapping("/{currencyCode}")
    public ResponseEntity<ApiResponse<ExchangeRateResponse>> getRate(
            @PathVariable("currencyCode") CurrencyCode currencyCode,
            @RequestParam(value = "date", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate date) {
        ExchangeRate rate = date != null
                ? exchangeRateService.getRateOnOrBefore(currencyCode, date)
                : exchangeRateService.getLatestRate(currencyCode);
        LocalDate effectiveDate = date != null ? date : LocalDate.now(clock);
        return publicRead(ExchangeRateResponse.from(rate, effectiveDate));
    }

    @GetMapping("/status")
    public ResponseEntity<ApiResponse<ExchangeRateStatusResponse>> getStatus() {
        return publicRead(ExchangeRateStatusResponse.from(exchangeRateService.getLatestRatesByCurrency()));
    }

    private static <T> ResponseEntity<ApiResponse<T>> publicRead(T data) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, CacheControlHeaders.PUBLIC_READ)
                .body(ApiResponse.success(data));
    }
}

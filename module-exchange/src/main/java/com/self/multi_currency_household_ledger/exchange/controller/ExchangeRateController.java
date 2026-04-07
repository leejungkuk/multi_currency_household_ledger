package com.self.multi_currency_household_ledger.exchange.controller;

import com.self.multi_currency_household_ledger.exchange.domain.CurrencyCode;
import com.self.multi_currency_household_ledger.exchange.dto.ExchangeRateResponse;
import com.self.multi_currency_household_ledger.exchange.service.ExchangeRateService;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/exchange-rates")
@RequiredArgsConstructor
public class ExchangeRateController {

    private final ExchangeRateService exchangeRateService;

    @GetMapping
    public ResponseEntity<List<ExchangeRateResponse>> getRatesByDate(
            @RequestParam("date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        List<ExchangeRateResponse> responses = exchangeRateService.getAllRatesByDate(date).stream()
                .map(ExchangeRateResponse::from)
                .toList();
        return ResponseEntity.ok(responses);
    }

    @GetMapping("/{currencyCode}")
    public ResponseEntity<ExchangeRateResponse> getLatestRate(
            @PathVariable("currencyCode") CurrencyCode currencyCode
    ) {
        ExchangeRateResponse response = ExchangeRateResponse.from(
                exchangeRateService.getLatestRate(currencyCode)
        );
        return ResponseEntity.ok(response);
    }

    @PostMapping("/fetch")
    public ResponseEntity<Void> fetchRates(
            @RequestParam("date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        exchangeRateService.fetchAndSaveRates(date);
        return ResponseEntity.ok().build();
    }
}

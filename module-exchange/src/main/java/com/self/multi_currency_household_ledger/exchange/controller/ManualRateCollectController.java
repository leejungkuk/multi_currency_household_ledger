package com.self.multi_currency_household_ledger.exchange.controller;

import com.self.multi_currency_household_ledger.common.dto.ApiResponse;
import com.self.multi_currency_household_ledger.exchange.domain.ExchangeRate;
import com.self.multi_currency_household_ledger.exchange.dto.ExchangeRateResponse;
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

// LocalSecurityConfig 와 활성 조건이 같아야 한다 — 체인만 prod 에서 빼고 이 컨트롤러를 두면
// 인증된 아무 회원이나 수집을 트리거할 수 있다(ArchitectureTest 가 각 빈의 prod 배제를 강제한다).
@Profile("local & !prod")
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

        exchangeRateService.fetchAndSaveRates(target);

        List<ExchangeRateResponse> responses = exchangeRateService.getAllRatesByDate(target).stream()
                .map(rate -> ExchangeRateResponse.from(rate, target))
                .toList();
        return ApiResponse.success(responses);
    }
}

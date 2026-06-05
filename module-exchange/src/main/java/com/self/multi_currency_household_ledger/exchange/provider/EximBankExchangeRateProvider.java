package com.self.multi_currency_household_ledger.exchange.provider;

import com.self.multi_currency_household_ledger.common.exception.BusinessException;
import com.self.multi_currency_household_ledger.exchange.domain.CurrencyCode;
import com.self.multi_currency_household_ledger.exchange.domain.FetchedRate;
import com.self.multi_currency_household_ledger.exchange.exception.ExchangeErrorCode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Slf4j
@Component
public class EximBankExchangeRateProvider implements ExchangeRateProvider {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final Set<String> SUPPORTED_CODES = Arrays.stream(CurrencyCode.values())
            .map(CurrencyCode::getApiCode)
            .collect(Collectors.toSet());

    private final RestClient restClient;
    private final String apiUrl;
    private final String apiKey;

    public EximBankExchangeRateProvider(
            RestClient.Builder restClientBuilder,
            @Value("${exchange.eximbank.api-url}") String apiUrl,
            @Value("${exchange.eximbank.api-key}") String apiKey
    ) {
        this.restClient = restClientBuilder.build();
        this.apiUrl = apiUrl;
        this.apiKey = apiKey;
    }

    @Override
    public List<FetchedRate> getExchangeRates(LocalDate date) {
        try {
            List<Map<String, Object>> response = restClient.get()
                    .uri(apiUrl + "?authkey={key}&searchdate={date}&data=AP01",
                            apiKey, date.format(DATE_FORMATTER))
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});

            if (response == null || response.isEmpty()) {
                log.warn("수출입은행 API 응답이 비어있습니다. date={}", date);
                return List.of();
            }

            return response.stream()
                    .filter(item -> SUPPORTED_CODES.contains(item.get("cur_unit")))
                    .map(this::mapToFetchedRate)
                    .flatMap(Optional::stream)
                    .toList();
        } catch (Exception e) {
            log.error("수출입은행 환율 API 호출 실패. date={}", date, e);
            throw new BusinessException(ExchangeErrorCode.EXCHANGE_API_ERROR);
        }
    }

    private Optional<FetchedRate> mapToFetchedRate(Map<String, Object> item) {
        String curUnit = (String) item.get("cur_unit");
        Object rawRate = item.get("deal_bas_r");

        if (rawRate == null) {
            log.warn("deal_bas_r is null. cur_unit={}", curUnit);
            return Optional.empty();
        }

        try {
            BigDecimal rate = new BigDecimal(rawRate.toString().replace(",", ""));
            if (rate.signum() <= 0) {
                log.warn("deal_bas_r is zero or negative. cur_unit={}, value={}", curUnit, rate);
                return Optional.empty();
            }
            CurrencyCode currencyCode = CurrencyCode.fromCode(curUnit);
            return Optional.of(new FetchedRate(currencyCode, rate));
        } catch (NumberFormatException e) {
            log.warn("deal_bas_r is non-numeric. cur_unit={}, value={}", curUnit, rawRate);
            return Optional.empty();
        }
    }
}

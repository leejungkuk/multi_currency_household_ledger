package com.self.multi_currency_household_ledger.exchange.provider;

import com.self.multi_currency_household_ledger.common.exception.BusinessException;
import com.self.multi_currency_household_ledger.exchange.domain.CurrencyCode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
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
    public List<ExchangeRateApiResponse> getExchangeRates(LocalDate date) {
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
                    .map(this::mapToResponse)
                    .toList();
        } catch (Exception e) {
            log.error("수출입은행 환율 API 호출 실패. date={}", date, e);
            throw new BusinessException("EXCHANGE_API_ERROR", "환율 정보를 가져오는데 실패했습니다: " + e.getMessage());
        }
    }

    private ExchangeRateApiResponse mapToResponse(Map<String, Object> item) {
        String dealBasR = ((String) item.get("deal_bas_r")).replace(",", "");
        return new ExchangeRateApiResponse(
                (String) item.get("cur_unit"),
                (String) item.get("cur_nm"),
                new BigDecimal(dealBasR)
        );
    }
}

package com.self.multi_currency_household_ledger.exchange.provider;

import com.self.multi_currency_household_ledger.common.exception.BusinessException;
import com.self.multi_currency_household_ledger.exchange.domain.CurrencyCode;
import com.self.multi_currency_household_ledger.exchange.domain.FetchedRate;
import com.self.multi_currency_household_ledger.exchange.exception.ExchangeErrorCode;
import java.math.BigDecimal;
import java.time.Duration;
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
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Slf4j
@Component
public class EximBankExchangeRateProvider implements ExchangeRateProvider {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final int MAX_TRANSIENT_RETRIES = 1;
    // 기준 통화(KRW)는 수출입은행 API 응답에 포함되지 않으므로 제외
    private static final Set<String> SUPPORTED_CODES = Arrays.stream(CurrencyCode.values())
            .filter(c -> !c.isBase())
            .map(CurrencyCode::getApiCode)
            .collect(Collectors.toSet());

    private final RestClient restClient;
    private final String apiUrl;
    private final String apiKey;

    public EximBankExchangeRateProvider(
            RestClient.Builder restClientBuilder,
            @Value("${exchange.eximbank.api-url}") String apiUrl,
            @Value("${exchange.eximbank.api-key}") String apiKey,
            @Value("${exchange.eximbank.connect-timeout:2s}") Duration connectTimeout,
            @Value("${exchange.eximbank.read-timeout:5s}") Duration readTimeout) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(readTimeout);
        this.restClient = restClientBuilder.requestFactory(requestFactory).build();
        this.apiUrl = apiUrl;
        this.apiKey = apiKey;
    }

    @Override
    public List<FetchedRate> getExchangeRates(LocalDate date) {
        List<Map<String, Object>> response = fetchResponse(date);

        if (response == null || response.isEmpty()) {
            log.warn("수출입은행 API 응답이 비어있습니다. date={}", date);
            return List.of();
        }

        validateResultCode(response);

        return response.stream()
                .filter(item -> SUPPORTED_CODES.contains(item.get("cur_unit")))
                .map(this::mapToFetchedRate)
                .flatMap(Optional::stream)
                .toList();
    }

    private List<Map<String, Object>> fetchResponse(LocalDate date) {
        for (int attempt = 0; attempt <= MAX_TRANSIENT_RETRIES; attempt++) {
            try {
                return restClient
                        .get()
                        .uri(apiUrl + "?authkey={key}&searchdate={date}&data=AP01", apiKey, date.format(DATE_FORMATTER))
                        .retrieve()
                        .body(new ParameterizedTypeReference<>() {});
            } catch (ResourceAccessException e) {
                if (attempt < MAX_TRANSIENT_RETRIES) {
                    log.warn("수출입은행 환율 API 일시 실패. date={}, retry={}", date, attempt + 1);
                    continue;
                }
                log.error("수출입은행 환율 API 호출 실패. date={}", date, e);
                throw new BusinessException(ExchangeErrorCode.EXCHANGE_API_ERROR);
            } catch (RestClientException e) {
                log.error("수출입은행 환율 API 호출 실패. date={}", date, e);
                throw new BusinessException(ExchangeErrorCode.EXCHANGE_API_ERROR);
            }
        }
        throw new BusinessException(ExchangeErrorCode.EXCHANGE_API_ERROR);
    }

    private void validateResultCode(List<Map<String, Object>> response) {
        response.stream()
                .map(item -> item.get("result"))
                .filter(result -> result != null)
                .map(Object::toString)
                .map(String::trim)
                .map(this::mapResultCode)
                .flatMap(Optional::stream)
                .findFirst()
                .ifPresent(errorCode -> {
                    log.warn("수출입은행 API result 오류. code={}", errorCode.getCode());
                    throw new BusinessException(errorCode);
                });
    }

    private Optional<ExchangeErrorCode> mapResultCode(String result) {
        return switch (result) {
            case "1", "2" -> Optional.empty();
            case "3" -> Optional.of(ExchangeErrorCode.EXCHANGE_API_AUTH_ERROR);
            case "4" -> Optional.of(ExchangeErrorCode.EXCHANGE_API_LIMIT_EXCEEDED);
            default -> Optional.of(ExchangeErrorCode.EXCHANGE_API_ERROR);
        };
    }

    private Optional<FetchedRate> mapToFetchedRate(Map<String, Object> item) {
        String curUnit = (String) item.get("cur_unit");
        // tts = 전신환매도율
        Object rawRate = item.get("tts");

        if (rawRate == null) {
            log.warn("tts is null. cur_unit={}", curUnit);
            return Optional.empty();
        }

        try {
            BigDecimal rate = new BigDecimal(rawRate.toString().replace(",", ""));
            if (rate.signum() <= 0) {
                log.warn("tts is zero or negative. cur_unit={}, value={}", curUnit, rate);
                return Optional.empty();
            }
            CurrencyCode currencyCode = CurrencyCode.fromCode(curUnit);
            return Optional.of(new FetchedRate(currencyCode, rate));
        } catch (NumberFormatException e) {
            log.warn("tts is non-numeric. cur_unit={}, value={}", curUnit, rawRate);
            return Optional.empty();
        }
    }
}

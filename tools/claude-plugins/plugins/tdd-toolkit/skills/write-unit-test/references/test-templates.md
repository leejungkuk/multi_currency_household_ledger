# 계층별 테스트 템플릿

대상 계층이 정해지면 해당 섹션의 템플릿을 복사해 시작점으로 쓴다. 모든 예시는 `module-exchange`의 실제 컨벤션에서 가져왔다. 패키지·클래스명·픽스처는 대상에 맞게 바꾼다.

## 목차
1. [도메인 엔티티](#1-도메인-엔티티)
2. [서비스](#2-서비스)
3. [컨트롤러](#3-컨트롤러)
4. [리포지토리](#4-리포지토리)
5. [외부 API provider (WireMock)](#5-외부-api-provider-wiremock)
6. [스케줄러](#6-스케줄러)

---

## 1. 도메인 엔티티

순수 JUnit. Spring 컨텍스트·목 없이 객체를 직접 생성해 비즈니스 메서드를 검증한다. 금액은 `isEqualByComparingTo`.

```java
package com.self.multi_currency_household_ledger.exchange.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ExchangeRateTest {

    @Nested
    @DisplayName("convertToKrw()")
    class ConvertToKrw {

        @Test
        @DisplayName("USD 100달러를 KRW로 변환한다")
        void converts_usd_to_krw() {
            ExchangeRate rate = ExchangeRate.of(CurrencyCode.USD, new BigDecimal("1300.00"), LocalDate.now());

            BigDecimal result = rate.convertToKrw(new BigDecimal("100"));

            assertThat(result).isEqualByComparingTo(new BigDecimal("130000"));
        }

        @Test
        @DisplayName("0 외화를 변환하면 0 KRW를 반환한다")
        void converts_zero_amount_to_krw() {
            ExchangeRate rate = ExchangeRate.of(CurrencyCode.USD, new BigDecimal("1300.00"), LocalDate.now());

            assertThat(rate.convertToKrw(BigDecimal.ZERO)).isEqualByComparingTo(BigDecimal.ZERO);
        }
    }
}
```

엣지 케이스 후보: 단위가 다른 통화(JPY는 100엔 단위), 소수점·반올림, 0/경계값. 의미 있는 것만 최대 3개.

---

## 2. 서비스

`@ExtendWith(MockitoExtension.class)` + `@InjectMocks` + `@Mock`. 협력 객체는 BDDMockito로 스텁한다. 결과·예외를 단언하고, 호출 횟수 검증(`verify`/`times`/`never`)은 그 자체가 검증 대상일 때만.

```java
package com.self.multi_currency_household_ledger.exchange.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.self.multi_currency_household_ledger.common.exception.BusinessException;
import com.self.multi_currency_household_ledger.exchange.domain.CurrencyCode;
import com.self.multi_currency_household_ledger.exchange.domain.ExchangeRate;
import com.self.multi_currency_household_ledger.exchange.domain.ExchangeRateRepository;
import com.self.multi_currency_household_ledger.exchange.provider.ExchangeRateProvider;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ExchangeRateServiceTest {

    @InjectMocks
    private ExchangeRateService exchangeRateService;

    @Mock
    private ExchangeRateRepository exchangeRateRepository;

    @Mock
    private ExchangeRateProvider exchangeRateProvider;

    private static final LocalDate DATE = LocalDate.of(2026, 4, 3);

    @Nested
    @DisplayName("getRate()")
    class GetRate {

        @Test
        @DisplayName("해당 날짜 환율이 있으면 반환한다")
        void returns_rate_for_date() {
            var rate = ExchangeRate.of(CurrencyCode.USD, new BigDecimal("1300.00"), DATE);
            given(exchangeRateRepository.findByCurrencyCodeAndBaseDate(CurrencyCode.USD, DATE))
                    .willReturn(Optional.of(rate));

            ExchangeRate result = exchangeRateService.getRate(CurrencyCode.USD, DATE);

            assertThat(result.getDealBasRate()).isEqualByComparingTo(new BigDecimal("1300.00"));
        }

        @Test
        @DisplayName("해당 날짜 이전 환율도 없으면 예외를 던진다")
        void throws_when_no_previous_rate_exists() {
            given(exchangeRateRepository.findByCurrencyCodeAndBaseDate(CurrencyCode.USD, DATE))
                    .willReturn(Optional.empty());
            given(exchangeRateRepository.findTopByCurrencyCodeAndBaseDateLessThanEqualOrderByBaseDateDesc(CurrencyCode.USD, DATE))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() -> exchangeRateService.getRate(CurrencyCode.USD, DATE))
                    .isInstanceOf(BusinessException.class);
        }
    }
}
```

- `void` 메서드 스텁은 `willDoNothing().given(mock).method(...)`, 예외는 `willThrow(new ...).given(mock).method(...)`.
- 호출 검증이 핵심인 케이스: 빈 입력 → `verify(repo, never()).save(...)`, N건 입력 → `verify(repo, times(n)).save(...)`.

---

## 3. 컨트롤러

`@WebMvcTest(controllers = X.class)`로 슬라이스만 로드. 서비스는 `@MockitoBean`으로 대체. `MockMvc`로 요청하고 `status()`·`jsonPath()`로 단언. 검증 실패·예외가 올바른 HTTP 상태/에러코드로 매핑되는지 확인한다.

```java
package com.self.multi_currency_household_ledger.exchange.controller;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.self.multi_currency_household_ledger.common.exception.BusinessException;
import com.self.multi_currency_household_ledger.exchange.domain.CurrencyCode;
import com.self.multi_currency_household_ledger.exchange.domain.ExchangeRate;
import com.self.multi_currency_household_ledger.exchange.service.ExchangeRateService;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = ExchangeRateController.class)
class ExchangeRateControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ExchangeRateService exchangeRateService;

    private static final LocalDate DATE = LocalDate.of(2026, 4, 3);

    @Test
    @DisplayName("GET /api/exchange-rates/{currencyCode} 특정 통화 최신 환율을 반환한다")
    void getLatestRate_returns_rate() throws Exception {
        var rate = ExchangeRate.of(CurrencyCode.USD, new BigDecimal("1300.00"), DATE);
        given(exchangeRateService.getLatestRate(CurrencyCode.USD)).willReturn(rate);

        mockMvc.perform(get("/api/exchange-rates/USD"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currencyCode").value("USD"))
                .andExpect(jsonPath("$.dealBasRate").value(1300.00));
    }

    @Test
    @DisplayName("환율 데이터가 없으면 400을 반환한다")
    void getLatestRate_returns_400_when_not_found() throws Exception {
        given(exchangeRateService.getLatestRate(CurrencyCode.GBP))
                .willThrow(new BusinessException("EXCHANGE_RATE_NOT_FOUND", "GBP 환율 정보가 존재하지 않습니다."));

        mockMvc.perform(get("/api/exchange-rates/GBP"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EXCHANGE_RATE_NOT_FOUND"));
    }
}
```

`GlobalExceptionHandler`가 `BusinessException`을 HTTP로 매핑하므로 슬라이스 테스트에서 에러 응답(`$.code`)까지 검증 가능하다.

---

## 4. 리포지토리

`@DataJpaTest` + `@AutoConfigureTestDatabase(replace = Replace.NONE)`로 **실 MySQL**에 붙는다(H2 자동 치환 금지가 이 프로젝트 규칙). `@Import`로 모듈 부트 클래스와 `TestJpaConfig`(JPA Auditing 활성화)를 올린다. `TestEntityManager`로 데이터를 심고 커스텀 쿼리 메서드를 검증한다.

```java
package com.self.multi_currency_household_ledger.exchange.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.self.multi_currency_household_ledger.exchange.TestExchangeApplication;
import com.self.multi_currency_household_ledger.exchange.TestJpaConfig;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;

@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Import({TestExchangeApplication.class, TestJpaConfig.class})
class ExchangeRateRepositoryTest {

    @Autowired
    private ExchangeRateRepository exchangeRateRepository;

    @Autowired
    private TestEntityManager em;

    private static final LocalDate DATE = LocalDate.of(2026, 4, 3);

    @Test
    @DisplayName("통화 코드와 날짜로 환율을 조회한다")
    void returns_rate_when_exists() {
        em.persist(ExchangeRate.of(CurrencyCode.USD, new BigDecimal("1300.00"), DATE));
        em.flush();

        Optional<ExchangeRate> result =
                exchangeRateRepository.findByCurrencyCodeAndBaseDate(CurrencyCode.USD, DATE);

        assertThat(result).isPresent();
        assertThat(result.get().getDealBasRate()).isEqualByComparingTo(new BigDecimal("1300.00"));
    }
}
```

### 모듈 test scaffolding (없으면 직접 생성한다)

`@DataJpaTest`는 부트 클래스를 찾아야 컨텍스트가 뜬다. `module-exchange`에는 `TestExchangeApplication`과 `TestJpaConfig`가 이미 있지만, **다른 모듈(예: `module-ledger`, `module-member`)에는 아직 없을 수 있다.** 이 경우 import만 적어두면 컴파일이 안 된다 — 테스트 파일을 쓰기 전에 해당 모듈 `src/test/java/.../<module>/` 아래에 두 클래스가 있는지 확인하고, **없으면 아래 패턴으로 실제 파일을 생성한 뒤** 테스트에서 import한다. (참조만 남기고 넘어가지 말 것 — 그러면 테스트가 영영 컴파일되지 않는다.)

`Test<Module>Application.java` (스캔 대상에 그 모듈 + `common`을 포함):
```java
package com.self.multi_currency_household_ledger.ledger;   // 대상 모듈에 맞게

import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {
        "com.self.multi_currency_household_ledger.ledger",   // 대상 모듈
        "com.self.multi_currency_household_ledger.common"
})
public class TestLedgerApplication {
}
```

`TestJpaConfig.java` (JPA Auditing 활성화 — `createdAt`/`updatedAt` 채움):
```java
package com.self.multi_currency_household_ledger.ledger;   // 대상 모듈에 맞게

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@TestConfiguration
@EnableJpaAuditing
public class TestJpaConfig {
}
```

그 외 주의:
- BaseEntity auditing(`createdAt`/`updatedAt`)은 `TestJpaConfig`의 `@EnableJpaAuditing` 덕분에 채워진다. 저장 후 id/createdAt/updatedAt이 채워지는지 확인하는 테스트를 한 개 두면 매핑 회귀를 잡는다.
- 정렬·`LessThanEqual`·범위 쿼리는 경계 데이터(같은 날짜 포함 여부, 다른 통화 무시)를 심어 검증한다.

---

## 5. 외부 API provider (WireMock)

외부 HTTP를 호출하는 `RestClient` 구현체는 WireMock 서버를 띄워 stub 응답으로 검증한다. dynamicPort로 띄우고 provider를 `wireMock.baseUrl()`로 직접 생성한다.

```java
package com.self.multi_currency_household_ledger.exchange.provider;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.self.multi_currency_household_ledger.common.exception.BusinessException;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class EximBankExchangeRateProviderTest {

    private WireMockServer wireMock;
    private EximBankExchangeRateProvider provider;

    @BeforeEach
    void setUp() {
        wireMock = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wireMock.start();
        provider = new EximBankExchangeRateProvider(
                RestClient.builder(), wireMock.baseUrl() + "/exchangeJSON", "test-api-key");
    }

    @AfterEach
    void tearDown() {
        wireMock.stop();
    }

    @Test
    @DisplayName("정상 응답 시 지원 통화만 필터링하여 반환한다")
    void returns_supported_currencies_only() {
        wireMock.stubFor(get(urlPathEqualTo("/exchangeJSON"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                [
                                    {"cur_unit":"USD","cur_nm":"미 달러","deal_bas_r":"1,300.50"},
                                    {"cur_unit":"AED","cur_nm":"아랍에미리트 디르함","deal_bas_r":"354.05"}
                                ]
                                """)));

        List<ExchangeRateApiResponse> result = provider.getExchangeRates(LocalDate.of(2026, 4, 3));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).curUnit()).isEqualTo("USD");
    }

    @Test
    @DisplayName("API 서버 에러 시 BusinessException을 던진다")
    void throws_exception_on_server_error() {
        wireMock.stubFor(get(urlPathEqualTo("/exchangeJSON"))
                .willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> provider.getExchangeRates(LocalDate.of(2026, 4, 3)))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo("EXCHANGE_API_ERROR"));
    }
}
```

검증 분기: 정상/빈 응답/HTTP 에러/외부 API 결과코드(예: 인증·한도·데이터없음)별로 한 케이스씩. JSON은 text block으로 작성한다.

---

## 6. 스케줄러

`@ExtendWith(MockitoExtension.class)`로 `TaskScheduler`를 목으로 둔다. 핵심은 **재시도 체인 검증**이다 — `TaskScheduler`가 목이라 예약된 `Runnable`은 자동 실행되지 않으므로, `ArgumentCaptor<Runnable>`로 캡처해 `.run()`으로 직접 돌리며 다음 예약을 단언한다.

```java
package com.self.multi_currency_household_ledger.exchange.scheduler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.self.multi_currency_household_ledger.common.exception.BusinessException;
import com.self.multi_currency_household_ledger.exchange.service.ExchangeRateService;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.TaskScheduler;

@ExtendWith(MockitoExtension.class)
class ExchangeRateSchedulerTest {

    @InjectMocks
    private ExchangeRateScheduler scheduler;

    @Mock
    private ExchangeRateService exchangeRateService;

    @Mock
    private TaskScheduler taskScheduler;

    @Test
    @DisplayName("fetchDailyRates 성공 시 서비스를 1회 호출한다")
    void fetchDailyRates_calls_service() {
        willDoNothing().given(exchangeRateService).fetchAndSaveRates(any(LocalDate.class));

        scheduler.fetchDailyRates();

        verify(exchangeRateService, times(1)).fetchAndSaveRates(any(LocalDate.class));
    }

    @Test
    @DisplayName("연속 실패 시 MAX_RETRY(2)까지만 재시도를 예약하고 이후 중단한다")
    void stops_scheduling_after_max_retries() {
        ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        willThrow(new BusinessException("EXCHANGE_API_ERROR", "API 호출 실패"))
                .given(exchangeRateService).fetchAndSaveRates(any(LocalDate.class));

        scheduler.fetchDailyRates();  // attempt=0 → retry(1) 예약
        verify(taskScheduler, times(1)).schedule(runnableCaptor.capture(), any(Instant.class));

        runnableCaptor.getValue().run();  // attempt=1 → retry(2) 예약
        verify(taskScheduler, times(2)).schedule(runnableCaptor.capture(), any(Instant.class));

        runnableCaptor.getValue().run();  // attempt=2 == MAX_RETRY → 추가 예약 없음
        verify(taskScheduler, times(2)).schedule(any(Runnable.class), any(Instant.class));
    }
}
```

주의: `scheduleRetry` 자체가 항상 `taskScheduler.schedule()`을 호출하므로, "재시도 안 함"을 `never()`로 단언하려다 실패하기 쉽다. 캡처한 `Runnable`을 실행하며 누적 호출 횟수(`times`)로 체인 종료를 검증하는 방식이 정확하다. 또 실행되지 않을 스텁을 만들면 `UnnecessaryStubbingException`이 나니, 한 테스트에서 전체 체인을 함께 검증한다.

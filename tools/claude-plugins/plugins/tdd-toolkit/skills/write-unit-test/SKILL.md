---
name: write-unit-test
description: >-
  이 멀티 모듈 Spring Boot(Java 21) 환율 가계부 프로젝트에서 단위/슬라이스 테스트를 작성할 때 사용한다.
  사용자가 "테스트 작성해줘", "테스트 추가", "test 만들어줘", "커버리지 채워줘", "이 메서드 검증",
  혹은 서비스/도메인 엔티티/컨트롤러/리포지토리/외부 API provider/스케줄러 코드를 새로 쓰거나 수정한 뒤
  테스트를 언급하면 반드시 이 스킬을 사용한다. 클래스명만 주거나 "방금 추가한 로직 테스트"처럼
  대상을 명시하지 않아도, 테스트 작성 의도가 보이면 적극적으로 발동한다. 이 스킬은 프로젝트의 실제
  테스트 컨벤션(@Nested + 한글 @DisplayName, AssertJ, BDDMockito, @DataJpaTest+실 MySQL,
  @WebMvcTest, WireMock)을 그대로 따르고, 작성 후 ./gradlew test 로 통과까지 검증한다.
---

# Unit Test 작성 (프로젝트 전용)

이 프로젝트의 테스트는 **계층마다 셋업 방식이 다르다**. 가장 흔한 실수는 한 가지 패턴(예: `@SpringBootTest`)을 모든 계층에 쓰는 것이다. 먼저 대상 클래스가 어느 계층인지 판별하고, 그 계층의 셋업을 정확히 골라야 빠르고 가벼운 테스트가 나온다.

## 작성 철학

기존 테스트들이 일관되게 따르는 원칙이며, 새 테스트도 동일하게 맞춘다.

- **Happy path 우선**: 핵심 성공 시나리오를 먼저 쓴다.
- **엣지 케이스는 의미 있는 것만, 최대 3개**: 경계값·실패 모드처럼 실제로 깨질 수 있는 것만. 테스트 수를 채우려고 늘리지 않는다.
- **동작을 검증, 내부 구현은 검증하지 않는다**: 결과값·상태 변화를 단언한다. `verify`로 호출 횟수를 단언하는 건 그 호출 자체가 검증 대상일 때만(예: "빈 응답이면 저장 안 함" → `never()`, "통화 수만큼 저장" → `times(n)`).
- **금액은 `BigDecimal` + `isEqualByComparingTo`**: `isEqualTo`는 scale 차이(`1300` vs `1300.00`)로 실패하므로 금액 비교엔 절대 쓰지 않는다.
- **간결하게**: 불필요한 셋업·중복 단언·과도한 목 금지. getter/setter, 단순 DTO, VO는 테스트하지 않는다.

## 명명 규칙 (엄수)

- 테스트 클래스: `<대상클래스>Test`
- 메서드명: 영어 snake_case `대상메서드_상황_기대결과` (예: `getRate_falls_back_to_nearest_previous_rate`)
- `@DisplayName`: **한글**로 의도 서술 (예: `"해당 날짜 환율이 없으면 직전 영업일 환율로 fallback한다"`)
- 한 메서드의 여러 시나리오는 `@Nested` 클래스로 묶고, `@Nested`에도 `@DisplayName`(보통 `"메서드명()"`)을 붙인다.

## 워크플로우

1. **대상 계층 판별** — 아래 표로 대상 클래스가 어느 계층인지 정하고 셋업을 고른다.
2. **형제 테스트 1개를 먼저 읽는다** — 같은 모듈/계층의 기존 테스트를 열어 import 스타일, 픽스처 생성 방식, 패키지 구조를 그대로 모방한다. 컨벤션은 추측하지 말고 실제 코드에서 확인한다.
3. **테스트 작성** — 해당 계층 템플릿(`references/test-templates.md`)을 복사해 채운다. 테스트 클래스는 프로덕션 클래스와 동일한 패키지의 `src/test/java/...` 아래에 둔다. 테스트가 의존하는 지원 클래스(예: 리포지토리 테스트의 `Test<Module>Application`/`TestJpaConfig`)가 그 모듈에 없으면, import만 적지 말고 **그 자리에서 함께 생성**한다.
4. **실행·검증** — 작성 직후 반드시 실행해서 통과를 확인한다:
   ```
   ./gradlew :<모듈명>:test --tests <패키지.클래스명>
   ```
   (예: `./gradlew :module-exchange:test --tests com.self.multi_currency_household_ledger.exchange.service.ExchangeRateServiceTest`)
   실패하면 출력을 읽고 수정 후 재실행한다. 통과할 때까지 반복한다. 통과를 확인하지 않고 "완료"라고 보고하지 않는다.

## 계층별 셋업 선택

| 대상 | 셋업 | 핵심 포인트 |
|------|------|-------------|
| **도메인 엔티티** (`ExchangeRate`, `CurrencyCode` 등 Rich Domain) | Spring 컨텍스트·목 **없이** 순수 JUnit | 객체를 직접 생성해 비즈니스 메서드의 반환값을 단언. 이 프로젝트 비즈니스 로직 대부분이 여기 있으니 가장 꼼꼼히. |
| **서비스** (오케스트레이션) | `@ExtendWith(MockitoExtension.class)` + `@InjectMocks` + `@Mock` | 리포지토리·provider를 목으로. BDDMockito `given()/willThrow()/willDoNothing()`. 결과·예외 단언 중심. |
| **컨트롤러** | `@WebMvcTest(controllers = X.class)` + `MockMvc` + `@MockitoBean` 서비스 | `mockMvc.perform(get(...).param(...))` → `status()`, `jsonPath()`. 검증/예외→HTTP 상태 매핑 확인. |
| **리포지토리** (Spring Data JPA) | `@DataJpaTest` + `@AutoConfigureTestDatabase(replace = Replace.NONE)` + `@Import({Test<Module>Application.class, TestJpaConfig.class})` | **실 MySQL** 사용(H2 치환 금지). `TestEntityManager`로 `persist`+`flush` 후 조회. 대상 모듈에 `Test<Module>Application`/`TestJpaConfig`가 없으면 **참조만 하지 말고 실제로 생성**한다(레퍼런스의 scaffolding 템플릿 참고). 안 만들면 컴파일 불가. |
| **외부 API provider** (`RestClient` 기반 클라이언트) | WireMock `WireMockServer`(dynamicPort) + `@BeforeEach`/`@AfterEach` | stub 응답으로 정상/빈/에러/결과코드 분기 검증. provider를 `wireMock.baseUrl()`로 직접 생성. |
| **스케줄러** | `@ExtendWith(MockitoExtension.class)` + `TaskScheduler` 목 | 재시도 체인은 `ArgumentCaptor<Runnable>`로 예약된 Runnable을 캡처해 `.run()`으로 직접 실행하며 검증. |

각 계층의 **전체 복사용 템플릿**은 `references/test-templates.md`에 있다. 대상 계층이 정해지면 해당 섹션을 읽어 그대로 시작점으로 쓴다.

## 출력 규칙

프로젝트 CLAUDE.md의 Output Guidelines를 따른다.

- 간결하게. 인사말·장황한 서론/결론 생략.
- **테스트 코드 위주**로 제공하고, 프로덕션 코드 전체를 다시 출력하지 않는다.
- 각 테스트 메서드가 무엇을 검증하는지 `@DisplayName`(한글)으로 드러나면 별도 주석은 최소화한다.
- 마지막에 실행 결과(통과 여부)를 한 줄로 보고한다.

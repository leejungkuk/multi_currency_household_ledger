# woni — 환율 가계부 API

해외 체류·여행·직구 사용자가 외화 지출을 **당일 환율(tts)로 자동 환산**해 기록하고, 월별로 원화 기준 수입·지출을 추적하는 멀티테넌트 가계부 백엔드.

## 무엇을 하는가

- **외화 지출 자동 환산** — 거래일에 적용 가능한 최신 전신환매도율(tts)로 원화 금액을 계산해 함께 저장한다.
- **환율 소급 보정** — 매일 환율을 수집한 뒤, 최근 며칠 내 외화 거래 중 *더 최신 환율을 적용할 수 있는 건*을 다시 계산한다(멱등·수렴). 주말·공휴일처럼 은행 고시가 없는 날 입력한 거래도 영업일 환율이 나오면 보정된다.
- **월별 리포트** — 원화 기준 순액과 통화별·카테고리별 소계를 낸다.
- **멀티테넌시** — 모든 사용자 데이터 경로가 JWT subject(UUID)를 필수 술어로 받는다.

지원 통화는 KRW + 외화 12종(USD·EUR·JPY·CNY·GBP·THB·HKD·SGD·IDR·MYR·AUD·NZD)이고 기준 통화는 KRW 고정이다.

## 기술 스택

| 영역 | 선택 |
|---|---|
| 런타임 | Java 21 · Spring Boot 3.4.1 |
| 데이터 | Postgres(Supabase) · Spring Data JPA · Flyway |
| 인증 | Spring Security OAuth2 Resource Server + Supabase Auth JWT(JWKS) |
| 외부 연동 | 수출입은행 환율 API (tts) |
| 테스트 | JUnit 5 · Testcontainers(Postgres) |
| 품질 게이트 | Spotless(palantirJavaFormat) · Error Prone(`-Werror`) · ArchUnit |

## 모듈 구조

의존 방향은 안쪽을 향하며 ArchUnit 테스트가 이를 빌드 게이트로 강제한다.

```
module-api ──┬──→ module-ledger ───┐
             ├──→ module-exchange ─┼──→ module-common
             └──→ module-member ───┘
```

| 모듈 | 책임 |
|---|---|
| `module-api` | 컨트롤러 · 보안 · 스케줄러. 유일한 실행 모듈 |
| `module-ledger` | 거래 · 카테고리 · 자산 도메인과 집계 |
| `module-exchange` | 환율 수집 · 조회 |
| `module-member` | 회원 식별 |
| `module-common` | 공용 DTO · 예외 · 에러 코드 |

`module-exchange` 는 `module-ledger` 를 모른다 — 환율 갱신에 따른 거래 재계산은 `module-api` 의 스케줄러가 오케스트레이션한다.

## 로컬 개발

### 사전 요구

- JDK 21
- Docker (테스트가 Testcontainers 로 Postgres 를 띄운다)
- `module-api/src/main/resources/application-secret.yml` — 개발 DB 접속 정보와 환율 API 키. git 에 올라가지 않으며 `local` 프로파일에서만 읽힌다.

### 실행 · 검증

```bash
./gradlew :module-api:bootRun   # local 프로파일이 자동 적용된다
./gradlew build                 # Spotless + Error Prone + ArchUnit + 전 모듈 테스트
```

`build` 는 Testcontainers 가 일회용 Postgres 를 띄우므로 개발·운영 DB 어느 쪽에도 접속하지 않는다.

### API 계약

`local` 프로파일에서 Swagger UI 를 쓴다 — http://localhost:8080/swagger-ui.html

계약 정본은 커밋된 스냅샷 `api-contract/openapi.json` 이다. 컨트롤러·DTO 로 API 형태를 바꾸면 같은 커밋에서 갱신한다(diff = 계약 변경).

```bash
./tools/update-api-snapshot.sh
```

## 설계 노트

- **금액은 항상 양수로 저장**하고 부호는 `transaction_type`(INCOME/EXPENSE)이 결정한다. 금액은 `numeric(19,2)`, 환율은 `numeric(19,6)`.
- **"오늘" 은 `Clock` 빈**(`Asia/Seoul`)으로 단일화한다 — 도메인·서비스는 `LocalDate.now(clock)` 을 쓰고 테스트는 `Clock.fixed` 를 주입한다.
- **스키마 변경은 Flyway 마이그레이션으로만** 한다(운영 `ddl-auto=validate`). 엔티티만 고치고 마이그레이션을 빠뜨리면 기동에서 걸린다.
- **JPY·IDR 은 100 단위 호가**라 환산할 때 나눈다. CNY 는 수출입은행이 역외 위안(`CNH`)으로만 제공해 API 코드만 다르고 저장 값은 `CNY` 다.
- 카테고리·자산 카탈로그는 **시스템 공용 고정 시드**다(회원별 소유 개념 없음).
- 에러는 `BusinessException(ErrorCode)` → `GlobalExceptionHandler` → `ErrorResponse(code, message, timestamp)` 로 통일하고, HTTP 상태는 `ErrorCode` 가 결정한다.

## 개발 규칙

- **TDD** — 새 기능은 테스트를 먼저 쓴다.
- **IDOR 테스트는 머지 게이트** — "타 회원 리소스 접근 → 404/403" 테스트 없이 사용자 데이터 API 를 머지하지 않는다.
- 커밋 메시지는 conventional commits, 브랜치는 `type/kebab-설명`.
- main 직접 push 금지 — 모든 변경은 PR 로 머지한다.

---

배포 설정과 운영 절차는 이 리포에 포함하지 않는다.

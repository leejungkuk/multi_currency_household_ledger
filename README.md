# woni — 환율 가계부 API

해외 체류·여행·직구 사용자가 외화 지출을 당일 환율(tts)로 자동 환산해 기록하고, 월별로 원화 기준 수입·지출을 추적하는 멀티테넌트 가계부 백엔드.

- **스택**: Spring Boot 3.4.1 · Java 21 · Postgres(Supabase) · Flyway · Supabase Auth(JWT)
- **모듈**: `module-api` · `module-ledger` · `module-exchange` · `module-member` · `module-common`

---

## 로컬 개발

### 사전 요구

- JDK 21
- Docker (테스트가 Testcontainers 로 Postgres 를 띄운다)
- `module-api/src/main/resources/application-secret.yml` — 개발 DB 접속 정보와 수출입은행 API 키. git 에 올라가지 않으며 `local` 프로파일에서만 읽힌다.

### 실행 · 검증

```bash
./gradlew :module-api:bootRun   # local 프로파일이 자동 적용된다
./gradlew build                 # Spotless + Error Prone + ArchUnit + 전 모듈 테스트
```

---

## 배포

### 1. 이미지 빌드

```bash
docker build -t woni-api:latest .
```

배포 대상이 ARM(Oracle A1 등)이 아니라 x86 이라면 `--platform linux/amd64` 를 붙인다.

### 2. 실행

```bash
docker run -d --name woni-api --restart unless-stopped -p 8080:8080 \
  -e SPRING_DATASOURCE_URL='jdbc:postgresql://<host>:5432/postgres' \
  -e SPRING_DATASOURCE_USERNAME='<user>' \
  -e SPRING_DATASOURCE_PASSWORD='<password>' \
  -e SUPABASE_JWT_ISSUER_URI='https://<project>.supabase.co/auth/v1' \
  -e EXCHANGE_EXIMBANK_API_KEY='<key>' \
  -e WONI_CORS_ALLOWED_ORIGINS='https://<app-domain>' \
  woni-api:latest
```

### 필수 환경변수

| 변수 | 설명 |
|---|---|
| `SPRING_DATASOURCE_URL` | 운영 Postgres JDBC URL. Supabase **direct connection** 을 쓴다(transaction pooler `:6543` 는 Flyway·prepared statement 와 충돌하므로 금지). |
| `SPRING_DATASOURCE_USERNAME` | DB 사용자 |
| `SPRING_DATASOURCE_PASSWORD` | DB 비밀번호 |
| `SUPABASE_JWT_ISSUER_URI` | Supabase Auth issuer. 기동 시 JWKS 를 가져오므로 잘못되면 **앱이 뜨지 않는다**. |
| `EXCHANGE_EXIMBANK_API_KEY` | 수출입은행 환율 API 키. 없으면 빈 생성 시점에 실패한다. |
| `WONI_CORS_ALLOWED_ORIGINS` | 허용 오리진(쉼표 구분). 와일드카드 `*` 가 들어가면 **기동을 거부한다**. |

### 선택 환경변수

| 변수 | 기본값 | 설명 |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `prod` | 이미지에 박혀 있다. **배포 환경에서 바꾸지 말 것**(아래 경고 참고). |
| `JAVA_OPTS` | `-XX:MaxRAMPercentage=60.0` | 메모리가 큰 인스턴스로 옮기면 올린다. |
| `SUPABASE_JWT_AUDIENCE` | `authenticated` | |
| `DB_POOL_SIZE` / `DB_POOL_MIN_IDLE` | `5` / `1` | |
| `LEDGER_RECALCULATION_WINDOW_DAYS` | `7` | 환율 재계산 보정창. 3~7 밖의 값이면 기동을 거부한다. |
| `EXIMBANK_CONNECT_TIMEOUT` / `EXIMBANK_READ_TIMEOUT` | `2s` / `5s` | |

### ⚠️ `local` 프로파일을 배포 환경에 쓰지 말 것

`SPRING_PROFILES_ACTIVE=local` 로 띄우면 SQL 로그가 켜질 뿐 아니라 **Swagger UI 와 수동 환율 수집 엔드포인트(`POST /api/v1/exchange-rates/collect`)가 토큰 없이 열린다**(`LocalSecurityConfig`).

### 헬스체크

```
GET /actuator/health   →   {"status":"UP"}
```

무토큰 공개이며 상세 정보는 노출하지 않는다. **DB 연결 상태가 판정에 포함**되므로 Supabase 가 끊기면 `DOWN` 이 된다. 컨테이너에도 같은 기준의 `HEALTHCHECK` 가 걸려 있다.

---

## 운영 노트

### 환율 수집 (매일 11:05 KST)

1. 수출입은행 AP01 에서 **tts(전신환매도율)** 를 받아 외화 12종을 저장한다(KRW 은 기준 통화라 행이 없다).
2. 수집에 성공하면 최근 7일 이내 외화 거래 중 **더 최신 환율을 적용할 수 있는 건**을 재계산한다(멱등·수렴).
3. 호출이 실패하면 11:00~14:00 사이 30분 간격으로 재시도하고, 14:00 이후에는 다음 영업일 보정창이 회수한다.

> 주말·공휴일에는 은행이 빈 응답을 주며 이는 정상(수집 성공)으로 처리된다. 그 날짜의 거래는 직전 영업일 환율로 폴백된다.

### 반드시 지킬 것

- **단일 인스턴스로만 실행한다.** 스케줄러에 분산 락이 없어 2대 이상이면 수집·재계산이 중복 실행된다.
- **놓친 날짜는 소급 수집되지 않는다.** 서버가 내려가 있던 기간만큼 환율에 구멍이 생기므로, 재배포 후 `GET /api/v1/exchange-rates/status` 로 통화별 `base_date` 를 확인하고 필요하면 `tools/backfill-exchange-rates.sh` 로 메운다.
- **서버에서 빌드하지 않는다.** 1GB 인스턴스에서는 Gradle 빌드가 OOM 으로 죽는다. 이미지는 로컬이나 CI 에서 만든다.

### DB 환경 분리

| 환경 | 대상 |
|---|---|
| 운영 | 운영 전용 Supabase 프로젝트 |
| 개발 · 테스트 | 기존 Supabase 프로젝트 (`application-secret.yml`) |

`./gradlew build` 는 Testcontainers 가 일회용 Postgres 를 띄우므로 어느 쪽에도 접속하지 않는다.

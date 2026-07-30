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
| `MANAGEMENT_SERVER_PORT` | `9091` | actuator 전용 내부 포트. 외부에 매핑하지 않는다. |
| `MANAGEMENT_SERVER_ADDRESS` | `0.0.0.0` | 컨테이너 배포는 기본값 그대로 둔다(같은 docker 네트워크의 수집기가 붙어야 한다). **컨테이너 없이 서버에서 jar 를 직접 돌린다면 `127.0.0.1` 로 잠근다** — 안 그러면 actuator 가 인터넷에 열린다. |
| `WONI_METRICS_APPLICATION` | `woni-api` | 메트릭 공통 라벨. 인스턴스·환경이 여럿이면 구분값을 준다. |

### ⚠️ `local` 프로파일을 배포 환경에 쓰지 말 것

`SPRING_PROFILES_ACTIVE=local` 로 띄우면 SQL 로그가 켜질 뿐 아니라 **Swagger UI 와 수동 환율 수집 엔드포인트(`POST /api/v1/exchange-rates/collect`)가 토큰 없이 열린다**(`LocalSecurityConfig`).

### 헬스체크

```
GET http://localhost:9091/actuator/health   →   {"status":"UP"}
```

actuator 는 애플리케이션 포트(8080)가 아니라 **내부 전용 9091** 에서만 서비스된다. 같은 포트에 붙는 `/actuator/prometheus` 본문에 JVM 상태·DB 풀 수치·엔드포인트별 URI 가 그대로 실리기 때문이며, 인증이 아니라 **포트를 열지 않는 것**이 방어선이다 — `docker run` 에서 9091 을 `-p` 로 매핑하지 마라.

상세 정보는 노출하지 않으며 **DB 연결 상태가 판정에 포함**되므로 Supabase 가 끊기면 `DOWN` 이 된다. 컨테이너 `HEALTHCHECK` 는 컨테이너 안에서 이 포트를 찌르므로 그대로 동작한다.

> **`docker run -P`(대문자)를 쓰지 마라.** `EXPOSE` 된 포트를 전부 랜덤 호스트 포트로 퍼블리시하므로 9091 이 그대로 열린다. 위 예시처럼 `-p 8080:8080` 만 명시한다.

외부에서 살아있는지 감시하려면(UptimeRobot 등) 공개 경로인 `GET /api/v1/assets` 를 쓴다 — 200 이면 앱과 DB 가 함께 살아 있다는 뜻이다(`/api/v1/categories` 는 `transactionType` 파라미터가 필수라 그냥 찌르면 400 이다).

---

## 모니터링

`monitoring/` 에 Prometheus · Loki · Alloy · Grafana 스택이 compose 로 들어 있다. 전부 OSS 라 비용이 들지 않고, 앱과 분리돼 있어 배포 호스트가 바뀌어도 스크랩 타깃만 갈아끼우면 된다.

```bash
cd monitoring
cp .env.example .env      # GRAFANA_ADMIN_PASSWORD 를 채운다
docker compose up -d
open http://localhost:3000
```

| 구성 | 역할 |
|---|---|
| Prometheus | 메트릭 저장 — 15일 / 2GB 중 먼저 걸리는 쪽까지 |
| Loki | 로그 저장 — 14일 |
| Alloy | 컨테이너 로그 수집 → Loki. Promtail 이 2026-03 EOL 이라 그 후속을 쓴다 |
| docker-socket-proxy | Alloy 대신 docker 소켓을 쥐고 **조회 API 만** 통과시킨다(아래 참고) |
| node-exporter | 호스트 CPU · 메모리 · 디스크 |
| Grafana | 조회. `woni 서비스 개요` · `Spring Boot 3.x Statistics` 대시보드가 자동 등록된다 |

`woni 서비스 개요` 는 요청률·5xx 에러율·응답 p50/p95/p99·JVM 힙·HikariCP 풀·호스트 자원·최근 ERROR 로그를 한 화면에 놓는다. `Spring Boot 3.x Statistics`(커뮤니티 대시보드 19004)는 GC·스레드·버퍼 풀·Logback 이벤트까지 파고든다. 호스트 지표를 더 보려면 Grafana UI 에서 대시보드 ID **1860**(Node Exporter Full)을 임포트한다.

### 스크랩 타깃 바꾸기

`monitoring/prometheus/targets/woni-api.json` 만 고치면 된다. Prometheus 재시작 없이 30초 안에 반영된다.

- 앱이 호스트에서 직접 도는 경우(기본값): `host.docker.internal:9091`
- 앱이 컨테이너인 경우: 앱을 같은 네트워크에 붙이고(`docker run --network woni-monitoring …`) `woni-api:9091`

### 포트를 외부에 열지 말 것

Grafana(3000)·Prometheus(9090)는 `127.0.0.1` 에만 바인딩돼 있고, Loki·Alloy·node-exporter·socket-proxy 는 호스트에 아예 퍼블리시하지 않는다(docker 내부망 전용). 메트릭과 로그는 인증 없이 읽히면 그 자체로 내부 정보다. 원격 호스트에서 볼 때도 포트를 열지 말고 SSH 터널을 쓴다.

```bash
ssh -L 3000:localhost:3000 <user>@<host>
```

> **리눅스에서 특히 중요**: docker 의 포트 퍼블리싱은 iptables 를 직접 건드려 **UFW 규칙을 우회**한다. `3000:3000` 처럼 주소 없이 쓰면 방화벽을 켜 둬도 인터넷에 열린다. `127.0.0.1:` 접두사를 지운 채로 배포하지 마라.

### docker 소켓을 Alloy 에 직접 주지 않는 이유

docker 소켓은 파일이 아니라 양방향 통신이라 **`:ro` 로 마운트해도 컨테이너 생성·exec 같은 쓰기 API 가 그대로 통한다** — 사실상 호스트 root 권한과 같다. 그래서 소켓은 `docker-socket-proxy` 만 쥐고, Alloy 는 `tcp://docker-socket-proxy:2375` 로 붙는다. 프록시는 `CONTAINERS`·`NETWORKS` 조회만 열고 나머지는 403 으로 끊는다(`POST=0`).

검증된 동작:

| 요청 | 결과 |
|---|---|
| `GET /containers/json`, `GET /networks` | 200 — 로그 수집에 필요 |
| `GET /images/json`, `/info`, `/secrets` | 403 |
| `POST /containers/create` | 403 |

### 알아둘 것

- **알림은 아직 붙이지 않았다.** 필요해지면 Grafana 내장 Alerting 에 Discord/Slack 웹훅을 건다. 다만 **VM 이 통째로 죽으면 이 스택도 같이 죽으므로**, 그 경우를 잡으려면 외부 워치독(UptimeRobot 무료 등)이 `GET /api/v1/assets` 를 찌르게 해 둬야 한다.
- **로그는 컨테이너 로그만 수집된다.** 앱을 `bootRun` 처럼 컨테이너 밖에서 돌리면 Loki 에 아무것도 안 쌓인다(메트릭은 정상 수집).
- **macOS 에서 node-exporter 는 Docker VM 의 지표를 보고한다** — 호스트 맥이 아니다. 리눅스 서버에서는 실제 호스트 지표가 나온다.

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

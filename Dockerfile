# syntax=docker/dockerfile:1

# 빌드 스테이지 — 테스트는 CI(.github/workflows/build.yml)가 Testcontainers 로 검증한다.
# 이미지 빌드 안에서 테스트를 돌리려면 Docker-in-Docker 가 필요하므로 여기서는 제외한다.
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /workspace
COPY . .
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon :module-api:bootJar -x test

FROM eclipse-temurin:21-jre-jammy

# 로그 타임스탬프를 KST 로 맞춘다. 앱의 "오늘" 판정은 Clock(Asia/Seoul) 빈이 담당하므로 이 값에 의존하지 않지만,
# JpaAuditing 이 기록하는 naive timestamp(ledger_entry·category 의 created_at/updated_at)는 JVM 기본 존을 쓴다.
# 익명 계정 정리 배치의 cutoff 가 그 컬럼과 비교되므로(ZoneId.systemDefault()), 이 값은 Clock 과 같은 존이어야 한다.
ENV TZ=Asia/Seoul

# curl 은 HEALTHCHECK 전용이다. 루트로 돌릴 이유가 없으므로 전용 계정을 만든다.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 1001 --create-home woni

WORKDIR /app
# bootJar 산출물만 집는다(-plain.jar 는 실행 불가한 라이브러리 jar 라 패턴에서 제외된다).
COPY --from=build /workspace/module-api/build/libs/module-api-*-SNAPSHOT.jar app.jar

USER woni
# 8080 = 공개 API. 9091 = actuator(health·prometheus) 전용이며 호스트로 매핑하지 않는다 —
# docker 내부망의 수집기만 붙어야 한다(application.yml 의 management.server.port 주석 참고).
EXPOSE 8080 9091

# 운영 프로파일을 기본값으로 못박는다. local 로 덮어쓰면 SQL 로그뿐 아니라 Swagger·수동 수집
# 엔드포인트까지 무토큰으로 열리므로(LocalSecurityConfig) 배포 환경에서 바꾸지 말 것.
ENV SPRING_PROFILES_ACTIVE=prod

# 1GB 인스턴스에서도 non-heap(메타스페이스·스레드 스택·코드캐시)이 남도록 60% 로 잡는다.
# 메모리가 큰 인스턴스로 옮기면 JAVA_OPTS 로 올린다.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=60.0"

# 두 색 배포의 투입 게이트 파일(application.yml 의 woni.deploy-gate.file). 배포 에이전트가 docker exec 로 이 파일을
# touch(투입)·rm(철수)한다. USER woni 가 쓸 수 있는 /tmp 에 두며, 컨테이너를 재생성하면 사라진다(게이트 닫힘).
ENV WONI_DEPLOY_GATE_FILE=/tmp/woni-deploy-gate-open

# start-period 는 1GB E2 의 느린 JVM 기동(1~2분)을 감안한 값이고, start-interval 은 그 구간을 2초마다 본다.
# healthy = 프로세스와 의존성(DB)이 정상이다. 게이트와 무관하다 — 루트 status 가 UP 또는 OUT_OF_SERVICE(게이트만
# 닫힘)면 healthy 라, 게이트 닫힌 새 색도 healthy 가 되어 에이전트가 스모크로 넘어간다. Supabase 가 끊기면 DOWN 이라
# 지금처럼 unhealthy 로 떨어진다. OUT_OF_SERVICE 는 503 이라 본문으로 판정한다(curl -f 를 쓰지 않는다).
# 컨테이너 내부에서 도는 검사라 내부 전용 management 포트를 그대로 쓴다.
HEALTHCHECK --interval=30s --timeout=5s --start-period=120s --start-interval=2s --retries=3 \
    CMD curl -sS http://localhost:9091/actuator/health | grep -qE '"status":"(UP|OUT_OF_SERVICE)"' || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]

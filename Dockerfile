# syntax=docker/dockerfile:1

# 빌드 스테이지 — 테스트는 CI(.github/workflows/build.yml)가 Testcontainers 로 검증한다.
# 이미지 빌드 안에서 테스트를 돌리려면 Docker-in-Docker 가 필요하므로 여기서는 제외한다.
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /workspace
COPY . .
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon :module-api:bootJar -x test

FROM eclipse-temurin:21-jre-jammy

# 로그 타임스탬프를 KST 로 맞춘다. 앱의 "오늘" 판정은 Clock(Asia/Seoul) 빈이 담당하므로 이 값에 의존하지 않는다.
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
EXPOSE 8080

# 운영 프로파일을 기본값으로 못박는다. local 로 덮어쓰면 SQL 로그뿐 아니라 Swagger·수동 수집
# 엔드포인트까지 무토큰으로 열리므로(LocalSecurityConfig) 배포 환경에서 바꾸지 말 것.
ENV SPRING_PROFILES_ACTIVE=prod

# 1GB 인스턴스에서도 non-heap(메타스페이스·스레드 스택·코드캐시)이 남도록 60% 로 잡는다.
# 메모리가 큰 인스턴스로 옮기면 JAVA_OPTS 로 올린다.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=60.0"

# start-period 는 1GB E2 의 느린 JVM 기동(1~2분)을 감안한 값이다.
# status 는 DB 연결까지 반영하므로 Supabase 가 끊기면 unhealthy 로 떨어진다.
HEALTHCHECK --interval=30s --timeout=5s --start-period=120s --retries=3 \
    CMD curl -fsS http://localhost:8080/actuator/health | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]

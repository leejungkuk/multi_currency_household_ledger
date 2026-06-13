#!/usr/bin/env bash
# API 계약 스냅샷 갱신 — springdoc /v3/api-docs → api-contract/openapi.json
#
# 이 스냅샷이 iOS(woni_app)와의 계약 SSOT 다. controller/dto 를 바꿨으면 이 스크립트로
# 스냅샷을 갱신해 같은 커밋에 포함한다. diff 가 곧 "API 계약 변경"이며, 프론트는 서버를
# 띄우지 않고 이 파일을 읽는다.
#
# 동작: 서버가 떠 있으면 바로 수집, 아니면 :module-api:bootRun 기동 → 수집 → 종료.
# 전제: 기동 경로는 MySQL(localhost:3306) + application-secret.yml 필요.

set -u

ROOT=$(git rev-parse --show-toplevel 2>/dev/null) || ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT" || exit 1

OUT="api-contract/openapi.json"
URL="http://localhost:8080/v3/api-docs"
BOOT_LOG="/tmp/api-snapshot-bootrun.log"

fetch() { curl -sf --max-time 5 "$URL"; }

SPEC=$(fetch || true)
STARTED=no
GRADLE_PID=""

if [ -z "$SPEC" ]; then
  echo "▶ 서버 미기동 — :module-api:bootRun 시작 (로그: $BOOT_LOG)"
  ./gradlew :module-api:bootRun >"$BOOT_LOG" 2>&1 &
  GRADLE_PID=$!
  STARTED=yes
  for _ in $(seq 1 60); do
    sleep 2
    SPEC=$(fetch || true)
    [ -n "$SPEC" ] && break
    if ! kill -0 "$GRADLE_PID" 2>/dev/null; then
      echo "❌ bootRun 이 비정상 종료되었습니다 — $BOOT_LOG 마지막 부분:"
      tail -5 "$BOOT_LOG"
      exit 1
    fi
  done
fi

stop_server() {
  [ "$STARTED" = yes ] || return 0
  kill "$GRADLE_PID" 2>/dev/null
  # gradle 이 fork 한 boot JVM 까지 종료
  pkill -f 'multi_currency_household_ledger.ApiApplication' 2>/dev/null
  wait "$GRADLE_PID" 2>/dev/null
}

if [ -z "$SPEC" ]; then
  echo "❌ ${URL} 수집 실패 (타임아웃)"
  stop_server
  exit 1
fi

mkdir -p api-contract
if ! printf '%s' "$SPEC" | python3 -m json.tool --sort-keys > "$OUT"; then
  echo "❌ 응답이 유효한 JSON 이 아닙니다"
  stop_server
  exit 1
fi
stop_server

echo "✅ ${OUT} 갱신 완료 ($(wc -c < "$OUT" | tr -d ' ')B)"
if git diff --quiet -- "$OUT" 2>/dev/null && git ls-files --error-unmatch "$OUT" >/dev/null 2>&1; then
  echo "ℹ️  계약 변경 없음 (기존 스냅샷과 동일)"
else
  echo "📡 계약이 변경되었습니다 — diff 를 확인하고 스냅샷을 커밋에 포함하세요:"
  git diff --stat -- "$OUT" 2>/dev/null | tail -2
fi
exit 0

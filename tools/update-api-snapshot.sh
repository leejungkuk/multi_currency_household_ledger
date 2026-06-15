#!/usr/bin/env bash
# API 계약 스냅샷 갱신 — springdoc /v3/api-docs → api-contract/openapi.json
#
# 이 스냅샷이 iOS(woni_app)와의 계약 SSOT 다. controller/dto 를 바꿨으면 이 스크립트로
# 스냅샷을 갱신해 같은 커밋에 포함한다. diff 가 곧 "API 계약 변경"이며, 프론트는 서버를
# 띄우지 않고 이 파일을 읽는다.
#
# 동작: 전체 컨텍스트를 Testcontainers 위에 부팅하는 생성기 테스트(:module-api:generateApiSnapshot)
# 로 raw 스펙(module-api/build/openapi-raw.json)을 만들고, json.tool --sort-keys 로 정규화한다.
# 전제: Docker 데몬(Testcontainers). 실 DB·Supabase 자격증명·네트워크는 필요 없다.
# (보안이 deny-by-default 라 생성기는 mock JWT 로 /v3/api-docs 에 접근한다.)

set -u

ROOT=$(git rev-parse --show-toplevel 2>/dev/null) || ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT" || exit 1

OUT="api-contract/openapi.json"
RAW="module-api/build/openapi-raw.json"

echo "▶ 계약 스냅샷 생성 (:module-api:generateApiSnapshot — Testcontainers)"
if ! ./gradlew --console=plain :module-api:generateApiSnapshot; then
  echo "❌ generateApiSnapshot 실패 (Docker 데몬이 떠 있는지 확인)"
  exit 1
fi

if [ ! -s "$RAW" ]; then
  echo "❌ raw 스펙이 생성되지 않았습니다: $RAW"
  exit 1
fi

mkdir -p api-contract
if ! python3 -m json.tool --sort-keys "$RAW" > "$OUT"; then
  echo "❌ 응답이 유효한 JSON 이 아닙니다"
  exit 1
fi

echo "✅ ${OUT} 갱신 완료 ($(wc -c < "$OUT" | tr -d ' ')B)"
if git diff --quiet -- "$OUT" 2>/dev/null && git ls-files --error-unmatch "$OUT" >/dev/null 2>&1; then
  echo "ℹ️  계약 변경 없음 (기존 스냅샷과 동일)"
else
  echo "📡 계약이 변경되었습니다 — diff 를 확인하고 스냅샷을 커밋에 포함하세요:"
  git diff --stat -- "$OUT" 2>/dev/null | tail -2
fi
exit 0

#!/usr/bin/env bash
# 백필된 환율 DB에서 iOS 오프라인 시드용 스냅샷을 추출한다.

set -u

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")/.." && pwd)
BUILD_DIR="$ROOT/build"
OUTPUT_FILE="$BUILD_DIR/exchange-rate-snapshot-2y.json"
BASE_URL="${BASE_URL:-http://127.0.0.1:8080}"
BASE_URL="${BASE_URL%/}"

TEMP_DIR=""

usage() {
  echo "사용법: tools/extract-seed-snapshot.sh <from:YYYY-MM-DD> <to:YYYY-MM-DD> [--dry-run]"
  echo "환경변수: BASE_URL (기본 http://127.0.0.1:8080)"
  echo "출력: $OUTPUT_FILE"
}

cleanup_temp() {
  if [[ -n "$TEMP_DIR" && -d "$TEMP_DIR" ]]; then
    rm -f \
      "$TEMP_DIR/response.json" \
      "$TEMP_DIR/last-response.json" \
      "$TEMP_DIR/data.ndjson" \
      "$TEMP_DIR/merged.json" \
      "$TEMP_DIR/candidate.json"
    rmdir "$TEMP_DIR" 2>/dev/null ||
      echo "임시 디렉터리가 남았습니다(수동 삭제 필요): $TEMP_DIR" >&2
  fi
  TEMP_DIR=""
}

validate_date() {
  local value="$1"
  [[ "$value" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]] &&
    python3 -c 'import datetime, sys; datetime.date.fromisoformat(sys.argv[1])' "$value" >/dev/null 2>&1
}

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "필수 명령을 찾을 수 없습니다: $1" >&2
    return 1
  fi
}

print_response_error() {
  local response_file="$1"
  if [[ -s "$response_file" ]]; then
    echo "응답 본문:" >&2
    jq -c . "$response_file" >&2 2>/dev/null || sed -n '1,20p' "$response_file" >&2
  fi
}

main() {
  if (($# < 2 || $# > 3)); then
    usage >&2
    return 1
  fi

  FROM="$1"
  TO="$2"
  shift 2

  DRY_RUN=false
  if (($# == 1)); then
    if [[ "$1" != "--dry-run" ]]; then
      echo "알 수 없는 플래그: $1" >&2
      usage >&2
      return 1
    fi
    DRY_RUN=true
  fi

  require_command python3 || return 1
  if ! validate_date "$FROM" || ! validate_date "$TO"; then
    echo "날짜는 실제 존재하는 YYYY-MM-DD 형식이어야 합니다." >&2
    usage >&2
    return 1
  fi
  if [[ "$FROM" > "$TO" ]]; then
    echo "from은 to보다 늦을 수 없습니다." >&2
    usage >&2
    return 1
  fi

  local kst_today
  kst_today=$(TZ=Asia/Seoul date +%F)
  if [[ "$TO" > "$kst_today" ]]; then
    echo "경고: to=${TO}가 KST 오늘보다 미래여서 ${kst_today}로 클램프합니다." >&2
    TO="$kst_today"
  fi
  if [[ "$FROM" > "$TO" ]]; then
    echo "클램프 후 from=${FROM}이 to=${TO}보다 늦습니다." >&2
    usage >&2
    return 1
  fi

  local target_output
  if ! target_output=$(python3 - "$FROM" "$TO" <<'PY'
import datetime
import sys

current = datetime.date.fromisoformat(sys.argv[1])
end = datetime.date.fromisoformat(sys.argv[2])
while current <= end:
    if current.weekday() < 5:
        print(current.isoformat())
    current += datetime.timedelta(days=1)
PY
  ); then
    echo "대상 날짜 산출에 실패했습니다." >&2
    return 1
  fi

  TARGET_DATES=()
  while IFS= read -r target_date; do
    [[ -n "$target_date" ]] && TARGET_DATES+=("$target_date")
  done <<<"$target_output"

  local target_count=${#TARGET_DATES[@]}
  if ((target_count == 0)); then
    echo "대상 평일이 0건입니다. from~to 범위를 확인하십시오." >&2
    return 1
  fi

  echo "대상: ${target_count}건 (${TARGET_DATES[0]} ~ ${TARGET_DATES[target_count - 1]}), BASE_URL=$BASE_URL"
  if [[ "$DRY_RUN" == true ]]; then
    return 0
  fi

  require_command curl || return 1
  require_command jq || return 1
  mkdir -p "$BUILD_DIR"
  TEMP_DIR=$(mktemp -d "$BUILD_DIR/.extract-seed-snapshot.XXXXXX") || return 1

  local response_file="$TEMP_DIR/response.json"
  local last_response_file="$TEMP_DIR/last-response.json"
  local data_file="$TEMP_DIR/data.ndjson"
  local merged_file="$TEMP_DIR/merged.json"
  local candidate_file="$TEMP_DIR/candidate.json"
  : >"$data_file"

  local partial_count=0
  local partial_dates=()
  local index date http_status curl_ok row_count violations
  for ((index = 0; index < target_count; index++)); do
    date="${TARGET_DATES[index]}"
    curl_ok=true
    if ! http_status=$(curl -sS -m 15 -o "$response_file" -w '%{http_code}' \
      "$BASE_URL/api/v1/exchange-rates/snapshot?date=$date"); then
      curl_ok=false
    fi

    if [[ "$curl_ok" != true || ! "$http_status" =~ ^2[0-9][0-9]$ ]] ||
      ! jq -e '.success == true and (.data | type == "array")' "$response_file" >/dev/null 2>&1; then
      echo "$date 스냅샷 조회 실패: HTTP ${http_status:-000}" >&2
      print_response_error "$response_file"
      return 1
    fi

    if ! violations=$(jq -c --arg requested_date "$date" '
      [.data[]
        | . as $item
        | select(
            ($item | type) != "object"
            or ($item.tts | type) != "number"
            or $item.tts <= 0
            or ($item.baseDate | type) != "string"
            or $item.baseDate > $requested_date
          )
        | {requestedDate: $requested_date, item: $item}
      ]
    ' "$response_file"); then
      echo "$date 응답 검증 중 JSON 처리에 실패했습니다." >&2
      return 1
    fi
    if [[ "$violations" != "[]" ]]; then
      echo "$date 응답에 tts <= 0 또는 baseDate > 요청일인 원소가 있습니다: $violations" >&2
      return 1
    fi

    row_count=$(jq -r '.data | length' "$response_file")
    if ((row_count < 12)); then
      partial_count=$((partial_count + 1))
      partial_dates+=("$date($row_count)")
      echo "$date: partial ($row_count)"
    else
      echo "$date: ${row_count}행"
    fi

    if ! jq -c '.data[]' "$response_file" >>"$data_file"; then
      echo "$date 응답 병합에 실패했습니다." >&2
      return 1
    fi
    if ! cp "$response_file" "$last_response_file"; then
      echo "$date 마지막 응답 보관에 실패했습니다." >&2
      return 1
    fi
  done

  # 오름차순 입력을 한 번만 순회해 (currencyCode, baseDate)의 첫 등장을 보존한다.
  if ! jq -s '
    reduce .[] as $item
      ({data: [], seen: {}};
        ($item.currencyCode + "|" + $item.baseDate) as $key
        | if .seen[$key]
          then .
          else .seen[$key] = true | .data += [$item]
          end)
    | .data
    | sort_by(.currencyCode, .baseDate)
  ' "$data_file" >"$merged_file"; then
    echo "스냅샷 병합과 dedup에 실패했습니다." >&2
    return 1
  fi

  # 서버가 직렬화한 마지막 응답 봉투를 재사용하고 data만 교체한다.
  if ! jq --slurpfile merged "$merged_file" '.data = $merged[0]' \
    "$last_response_file" >"$candidate_file"; then
    echo "최종 스냅샷 생성에 실패했습니다." >&2
    return 1
  fi

  # MIN(baseDate)는 == 이 아니라 <= 로 본다. from이 은행 휴무 평일이면 서버가 직전 영업일로 정상 폴백하므로
  # == 는 올바른 추출을 거부한다. <= 는 "커버리지가 from까지 닿았는가"라는 진짜 불변식을 유지한다
  # (DB 하한이 from보다 위면 그 날짜 스냅샷이 비어 MIN > from 이 되어 걸린다).
  # 실행별 하한 확정값(step3의 MIN == 2024-07-29)은 실행 AC에서 확인한다.
  local expected_currencies
  expected_currencies='["AUD","CNY","EUR","GBP","HKD","IDR","JPY","MYR","NZD","SGD","THB","USD"]'
  if ! jq -e --arg from "$FROM" --argjson expected_currencies "$expected_currencies" '
    (keys_unsorted == ["success", "code", "data", "message", "timestamp"])
    and (.success == true)
    and (.data | type == "array" and length > 0)
    and (all(.data[];
      (keys_unsorted == ["currencyCode", "currencyName", "tts", "baseDate", "stale"])))
    and (([.data[].currencyCode] | unique | sort) == $expected_currencies)
    and (([.data[] | [.currencyCode, .baseDate]] | length)
      == ([.data[] | [.currencyCode, .baseDate]] | unique | length))
    and (([.data[].baseDate] | min) <= $from)
  ' "$candidate_file" >/dev/null; then
    echo "최종 검증 실패: 봉투/필드 순서, 통화 집합, dedup 또는 MIN(baseDate)을 확인하십시오." >&2
    return 1
  fi

  if ! mv "$candidate_file" "$OUTPUT_FILE"; then
    echo "최종 스냅샷 이동에 실패했습니다: $OUTPUT_FILE" >&2
    return 1
  fi

  local total_rows currencies min_base_date max_base_date file_size
  total_rows=$(jq -r '.data | length' "$OUTPUT_FILE")
  currencies=$(jq -r '[.data[].currencyCode] | unique | sort | join(",")' "$OUTPUT_FILE")
  min_base_date=$(jq -r '[.data[].baseDate] | min' "$OUTPUT_FILE")
  max_base_date=$(jq -r '[.data[].baseDate] | max' "$OUTPUT_FILE")
  file_size=$(wc -c <"$OUTPUT_FILE" | tr -d ' ')

  echo "요약: 총 행수=$total_rows, 통화 집합=$currencies, MIN(baseDate)=$min_base_date, MAX(baseDate)=$max_base_date"
  echo "통화별 행수:"
  jq -r '.data | group_by(.currencyCode)[] | "  \(.[0].currencyCode): \(length)"' "$OUTPUT_FILE"
  echo "부분 응답: ${partial_count}일"
  if ((partial_count > 0)); then
    printf '  %s\n' "${partial_dates[*]}"
  fi
  echo "파일 크기: ${file_size} bytes"
  echo "출력: $OUTPUT_FILE"
}

# 함수 단위 검증은 반드시 bash로 한다: bash -c 'source tools/extract-seed-snapshot.sh; validate_date 2024-07-29'
# (zsh에는 BASH_SOURCE가 없어 아래 조건이 항상 참이 되어 source만 해도 main이 실행된다)
if [[ "${BASH_SOURCE[0]:-$0}" == "$0" ]]; then
  trap cleanup_temp EXIT
  trap 'exit 130' INT
  trap 'exit 143' TERM
  main "$@"
fi

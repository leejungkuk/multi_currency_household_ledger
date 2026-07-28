#!/usr/bin/env bash
# 수출입은행 과거 환율을 local 프로필의 수동 수집 API로 백필한다.

set -u

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")/.." && pwd)
BUILD_DIR="$ROOT/build"
CHECKPOINT="$BUILD_DIR/backfill-checkpoint.json"
BASE_URL="${BASE_URL:-http://127.0.0.1:8080}"
BASE_URL="${BASE_URL%/}"

CURRENT_TEMP=""

usage() {
  echo "사용법: tools/backfill-exchange-rates.sh <from:YYYY-MM-DD> <to:YYYY-MM-DD> [--force] [--yes] [--dry-run]"
  echo "환경변수: BASE_URL (기본 http://127.0.0.1:8080)"
}

# 496회 장시간 실행 중 Ctrl-C가 현실적이므로 임시파일을 남기지 않는다.
# trap 등록은 직접 실행 분기(파일 하단)에서만 한다 — source 시 호출자 셸의 trap을 덮어쓰지 않기 위해서다.
cleanup_temp() {
  [[ -n "$CURRENT_TEMP" ]] && rm -f "$CURRENT_TEMP"
  CURRENT_TEMP=""
}

is_blocked_kst_time() {
  local hhmm="${1:-$(TZ=Asia/Seoul date +%H%M)}"
  [[ "$hhmm" =~ ^[0-9]{4}$ ]] || return 2
  ((10#$hhmm >= 1100 && 10#$hhmm <= 1430))
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

write_checkpoint() {
  local temporary
  temporary=$(mktemp "$BUILD_DIR/.backfill-checkpoint.XXXXXX") || return 1
  CURRENT_TEMP="$temporary"
  if ! jq -n \
    --arg from "$FROM" \
    --arg to "$TO" \
    --arg last_success "$LAST_SUCCESS" \
    --arg next_pending "$NEXT_PENDING" \
    --argjson complete "$COMPLETE_COUNT" \
    --argjson empty "$EMPTY_COUNT" \
    --argjson partial "$PARTIAL_COUNT" \
    --argjson fail "$FAIL_COUNT" \
    --argjson partial_dates "$PARTIAL_DATES" \
    --arg run_id "$RUN_ID" \
    '{
      from: $from,
      to: $to,
      last_success: (if $last_success == "" then null else $last_success end),
      next_pending: (if $next_pending == "" then null else $next_pending end),
      counts: {complete: $complete, empty: $empty, partial: $partial, fail: $fail},
      partial_dates: $partial_dates,
      run_id: $run_id
    }' >"$temporary"; then
    rm -f "$temporary"
    CURRENT_TEMP=""
    return 1
  fi
  if ! mv "$temporary" "$CHECKPOINT"; then
    rm -f "$temporary"
    CURRENT_TEMP=""
    return 1
  fi
  CURRENT_TEMP=""
}

print_summary() {
  echo "요약: complete=$COMPLETE_COUNT empty=$EMPTY_COUNT partial=$PARTIAL_COUNT fail=$FAIL_COUNT"
  if ((PARTIAL_COUNT > 0)); then
    echo "partial 날짜: $(jq -r 'join(", ")' <<<"$PARTIAL_DATES")"
  fi
}

guard_start() {
  if [[ "$FORCE" == true ]]; then
    return 0
  fi
  if is_blocked_kst_time; then
    echo "KST 11:00~14:30에는 백필을 실행할 수 없습니다. 백필용 서버도 함께 내리십시오. (--force로만 우회 가능)" >&2
    return 1
  fi
}

preflight() {
  local response_file http_status curl_ok=true
  response_file=$(mktemp "$BUILD_DIR/.backfill-preflight.XXXXXX") || return 1
  CURRENT_TEMP="$response_file"
  if ! http_status=$(curl -sS -m 10 -o "$response_file" -w '%{http_code}' \
    "$BASE_URL/api/v1/exchange-rates/status"); then
    curl_ok=false
  fi

  if [[ "$curl_ok" != true || "$http_status" != "200" ]] ||
    ! jq -e '.success == true and (.data.rates | type == "array")' "$response_file" >/dev/null 2>&1; then
    echo "preflight 실패: $BASE_URL/api/v1/exchange-rates/status (HTTP ${http_status:-000})" >&2
    cleanup_temp
    return 1
  fi

  local currency_count latest_base_date
  currency_count=$(jq -r '.data.rates | length' "$response_file")
  latest_base_date=$(jq -r '.data.rates | map(.base_date) | max // "없음"' "$response_file")
  cleanup_temp
  echo "preflight 성공: 통화 수=$currency_count, 최신 base_date=$latest_base_date"
}

main() {
  if (($# < 2)); then
    usage >&2
    return 1
  fi

  FROM="$1"
  TO="$2"
  shift 2

  FORCE=false
  YES=false
  DRY_RUN=false
  while (($# > 0)); do
    case "$1" in
      --force) FORCE=true ;;
      --yes) YES=true ;;
      --dry-run) DRY_RUN=true ;;
      *)
        echo "알 수 없는 플래그: $1" >&2
        usage >&2
        return 1
        ;;
    esac
    shift
  done

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

  # 날짜 산출은 command substitution으로 받아 종료 상태를 명시적으로 검사한다.
  # process substitution으로 읽으면 python3 실패가 전파되지 않아 "0건 + exit 0"으로 오판한다.
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
    echo "대상 영업일이 0건입니다. from~to 범위를 확인하십시오." >&2
    return 1
  fi

  echo "대상: ${target_count}건 (${TARGET_DATES[0]} ~ ${TARGET_DATES[target_count - 1]}), BASE_URL=$BASE_URL"
  if [[ "$DRY_RUN" == true ]]; then
    return 0
  fi

  if ! guard_start; then
    return 1
  fi

  require_command curl || return 1
  require_command jq || return 1
  mkdir -p "$BUILD_DIR"

  if ! preflight; then
    return 1
  fi

  if [[ "$YES" != true ]]; then
    local answer
    read -r -p "계속하시겠습니까? [y/N] " answer
    case "$answer" in
      y | Y) ;;
      *)
        echo "취소했습니다."
        return 1
        ;;
    esac
  fi

  COMPLETE_COUNT=0
  EMPTY_COUNT=0
  PARTIAL_COUNT=0
  FAIL_COUNT=0
  PARTIAL_DATES='[]'
  LAST_SUCCESS=""
  NEXT_PENDING="${TARGET_DATES[0]}"
  RUN_ID="$(TZ=Asia/Seoul date +%Y%m%dT%H%M%S%z)-$$"

  # 중단 후 재개(next_pending을 새 from으로 재실행)면 이전 집계를 이어받는다.
  # 그러지 않으면 재개분이 이전 기록을 덮어써 "complete+empty+partial = 전체 대상 수"와 partial 목록을 증명할 수 없다.
  if [[ -f "$CHECKPOINT" ]] &&
    jq -e --arg from "$FROM" --arg to "$TO" '.to == $to and .next_pending == $from' "$CHECKPOINT" >/dev/null 2>&1; then
    COMPLETE_COUNT=$(jq -r '.counts.complete' "$CHECKPOINT")
    EMPTY_COUNT=$(jq -r '.counts.empty' "$CHECKPOINT")
    PARTIAL_COUNT=$(jq -r '.counts.partial' "$CHECKPOINT")
    PARTIAL_DATES=$(jq -c '.partial_dates' "$CHECKPOINT")
    LAST_SUCCESS=$(jq -r '.last_success // ""' "$CHECKPOINT")
    FROM="$(jq -r '.from' "$CHECKPOINT")"
    echo "이전 checkpoint에서 재개합니다: complete=$COMPLETE_COUNT empty=$EMPTY_COUNT partial=$PARTIAL_COUNT (최초 from=$FROM)"
  fi

  local index date attempt backoff http_status curl_ok response_file row_count
  for ((index = 0; index < target_count; index++)); do
    date="${TARGET_DATES[index]}"
    NEXT_PENDING="$date"

    if [[ "$FORCE" != true ]] && is_blocked_kst_time; then
      write_checkpoint || echo "checkpoint 기록에 실패했습니다: $CHECKPOINT" >&2
      echo "KST 11:00~14:30에 진입해 중단합니다. checkpoint에서 재개하고 백필용 서버도 함께 내리십시오." >&2
      print_summary
      return 1
    fi

    response_file=$(mktemp "$BUILD_DIR/.backfill-response.XXXXXX") || return 1
    CURRENT_TEMP="$response_file"
    row_count=""
    for attempt in 1 2 3; do
      curl_ok=true
      if ! http_status=$(curl -sS -m 30 -o "$response_file" -w '%{http_code}' -X POST \
        "$BASE_URL/api/v1/exchange-rates/collect?date=$date"); then
        curl_ok=false
      fi

      if [[ "$curl_ok" == true && "$http_status" =~ ^2[0-9][0-9]$ ]] &&
        jq -e '.success == true and (.data | type == "array")' "$response_file" >/dev/null 2>&1; then
        row_count=$(jq -r '.data | length' "$response_file")
        break
      fi

      if ((attempt < 3)); then
        if ((attempt == 1)); then backoff=2; else backoff=5; fi
        echo "$date 수집 실패(HTTP ${http_status:-000}), ${backoff}초 후 재시도합니다." >&2
        sleep "$backoff"
      fi
    done
    cleanup_temp

    if [[ -z "$row_count" ]]; then
      ((FAIL_COUNT += 1))
      write_checkpoint || echo "checkpoint 기록에 실패했습니다: $CHECKPOINT" >&2
      echo "$date 수집이 재시도 후에도 실패했습니다. checkpoint에서 재개하십시오." >&2
      print_summary
      return 1
    fi

    if ((row_count == 12)); then
      ((COMPLETE_COUNT += 1))
      echo "$date: complete (12)"
    elif ((row_count == 0)); then
      ((EMPTY_COUNT += 1))
      echo "$date: empty (0)"
    elif ((row_count >= 1 && row_count <= 11)); then
      ((PARTIAL_COUNT += 1))
      PARTIAL_DATES=$(jq --arg date "$date" '. + [$date]' <<<"$PARTIAL_DATES")
      echo "$date: partial ($row_count)"
    else
      ((FAIL_COUNT += 1))
      write_checkpoint || echo "checkpoint 기록에 실패했습니다: $CHECKPOINT" >&2
      echo "$date 응답의 data 길이가 예상 범위(0~12)를 벗어났습니다: $row_count" >&2
      print_summary
      return 1
    fi

    LAST_SUCCESS="$date"
    if ((index + 1 < target_count)); then
      NEXT_PENDING="${TARGET_DATES[index + 1]}"
    else
      NEXT_PENDING=""
    fi
    if ! write_checkpoint; then
      echo "checkpoint 기록에 실패했습니다: $CHECKPOINT" >&2
      return 1
    fi

    if ((index + 1 < target_count)); then
      sleep 0.3
    fi
  done

  print_summary
  if ((PARTIAL_COUNT > 0)); then
    return 2
  fi
  return 0
}

# 함수 단위 검증은 반드시 bash로 한다: bash -c 'source tools/backfill-exchange-rates.sh; is_blocked_kst_time 1200'
# (zsh에는 BASH_SOURCE가 없어 아래 조건이 항상 참이 되어 source만 해도 main이 실행된다)
if [[ "${BASH_SOURCE[0]:-$0}" == "$0" ]]; then
  # INT/TERM은 정리만 하고 끝내면 신호를 삼켜 백필이 계속된다 — 반드시 종료해 EXIT 정리를 태운다.
  trap cleanup_temp EXIT
  trap 'exit 130' INT
  trap 'exit 143' TERM
  main "$@"
fi

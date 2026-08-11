#!/usr/bin/env bash
# woni 관측 스택을 실행 중 api 이미지의 revision 커밋과 동기화한다.
# 설계 SSOT는 .claude/plan/monitoring-sync-plan.md §4다. systemd timer(300초)가 인자 없이 호출한다.
# Linux 호스트 전용이다(flock·GNU timeout). macOS에서는 bash -n·shellcheck·--dry-run만 지원한다.
# --dry-run은 docker·monitoring/·상태 파일에 쓰지 않는다. 판정에 필요한 git fetch만 예외로 허용한다.
#
# 종료 코드 계약: 회복 가능한 실패는 자체 억제 알림 후 0, 복원까지 실패해 관측 스택이 down인 경우만 1이다.
# 설정·필수 명령 부재나 셸 비정상 종료는 systemd OnFailure의 "스택 상태 미확인" 경로가 담당한다.

# jq 필터의 $tree·$reason 등은 --arg로 넘기는 jq 변수다.
# shellcheck disable=SC2016

set -euo pipefail

readonly CONTAINER="woni-api"
readonly API_SERVICE="api"
readonly CADDY_SERVICE="caddy"
readonly HEALTH_POLL_SEC=10
readonly SETTLE_SEC=60
readonly FAILURE_THRESHOLD=3
readonly REMINDER_INTERVAL_SEC=86400
REPO_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)   # readonly 아님: self_test_setup이 케이스 디렉터리로 격리한다

STATE_DIR="${STATE_DIRECTORY:-/var/lib/woni-deploy}"
STATE_FILE="$STATE_DIR/state.json"
DEPLOY_DIR=""
MONITORING_DIR=""
BACKUP_DIR=""
NTFY_TOPIC=""
SMOKE_HOST=""
DRY_RUN=false
RESYNC=false
TEMP_FILES=()
TEMP_DIRS=()
SMOKE_DETAIL=""

log() { printf '%s %s\n' "$(TZ=Asia/Seoul date '+%F %T%z')" "$*"; }
warn() { log "$*" >&2; }

cleanup() {
  ((${#TEMP_FILES[@]} > 0)) && rm -f -- "${TEMP_FILES[@]}"
  ((${#TEMP_DIRS[@]} > 0)) && rm -rf -- "${TEMP_DIRS[@]}"
  return 0
}

new_temp_file() {
  local __name="$1" __dir="${2:-${TMPDIR:-/tmp}}" __path
  __path=$(mktemp "${__dir%/}/.woni-monitoring-sync.XXXXXX") || return 1
  TEMP_FILES+=("$__path")
  printf -v "$__name" '%s' "$__path"
}

new_temp_dir() {
  local __name="$1" __path
  __path=$(mktemp -d "${TMPDIR:-/tmp}/.woni-monitoring-sync.XXXXXX") || return 1
  TEMP_DIRS+=("$__path")
  printf -v "$__name" '%s' "$__path"
}

usage() {
  cat <<'EOF'
사용법: tools/monitoring-sync.sh [모드]
  (없음)       1회 판정·동기화 (systemd가 호출)
  --dry-run    판정만 출력. docker·monitoring/·상태 파일에 쓰지 않는다
               단, revision 객체 확보를 위한 git fetch는 예외로 허용한다
  --retry      실패 기록·카운터·미완료 marker를 비운다
  --resync     synced_tree를 무시하고 강제 동기화한다
  --self-test  외부 시스템을 스텁으로 대체해 핵심 복원 계약을 실행 검증한다
환경변수: WONI_AGENT_CONF (기본 $HOME/woni/deploy/agent.conf)
EOF
}

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "필수 명령을 찾을 수 없습니다: $1" >&2
    return 1
  fi
}

load_config() {
  local conf="${WONI_AGENT_CONF:-${HOME:-}/woni/deploy/agent.conf}"
  if [[ ! -r "$conf" ]]; then
    echo "설정 파일을 읽을 수 없습니다: $conf" >&2
    return 1
  fi
  # shellcheck disable=SC1090
  source "$conf"
  DEPLOY_DIR="${WONI_DEPLOY_DIR:-}"
  NTFY_TOPIC="${WONI_NTFY_TOPIC:-}"
  SMOKE_HOST="${WONI_SMOKE_HOST:-}"
  if [[ -z "$DEPLOY_DIR" || -z "$NTFY_TOPIC" || -z "$SMOKE_HOST" ]]; then
    echo "WONI_DEPLOY_DIR·WONI_NTFY_TOPIC·WONI_SMOKE_HOST가 모두 있어야 합니다: $conf" >&2
    return 1
  fi
  MONITORING_DIR="$DEPLOY_DIR/../monitoring"
  BACKUP_DIR="$MONITORING_DIR.bak"
}

notify() {
  local message="$1" priority=default
  case "$message" in
    🚨* | 🔴*) priority=urgent ;;
    *) ;;
  esac
  curl -sS -m 10 -H "Priority: $priority" -d "$message" "https://ntfy.sh/$NTFY_TOPIC" >/dev/null 2>&1 && return 0
  warn "ntfy 전송에 실패했습니다."
  return 1
}

state_init() {
  mkdir -p "$STATE_DIR" || return 1
  [[ -f "$STATE_FILE" ]] || printf '{}\n' >"$STATE_FILE"
}

state_get() {
  local filter="$1" fallback="${2:-}"
  [[ -f "$STATE_FILE" ]] || {
    printf '%s\n' "$fallback"
    return 0
  }
  jq -r -c --arg fallback "$fallback" "$filter // \$fallback" "$STATE_FILE" 2>/dev/null ||
    printf '%s\n' "$fallback"
}

# 호출자가 성공 여부를 반드시 처리한다. synced_tree·marker·실패 카운터를 soft write하지 않는다.
hard_state_write() {
  local filter="$1" temp
  shift
  if new_temp_file temp "$STATE_DIR"; then
    :
  else
    warn "관측 동기화 상태 파일 임시본을 만들 수 없습니다."
    return 1
  fi
  if jq "$@" "$filter" "$STATE_FILE" >"$temp" && mv -f "$temp" "$STATE_FILE"; then
    return 0
  fi
  warn "관측 동기화 상태 파일 갱신에 실패했습니다."
  return 1
}

container_revision() {
  local rev
  rev=$(docker inspect "$CONTAINER" --format '{{index .Config.Labels "org.opencontainers.image.revision"}}' 2>/dev/null) || return 0
  [[ "$rev" =~ ^[0-9a-f]{40}$ ]] && printf '%s\n' "$rev"
  return 0
}

classify_timeout() {
  local rc="$1" label="$2"
  case "$rc" in
    124) warn "$label 작업이 시간 제한을 초과했습니다(rc=124)." ;;
    137) warn "$label 작업이 종료 유예 후 강제 종료됐습니다(rc=137)." ;;
    *) warn "$label 작업에 실패했습니다(rc=$rc)." ;;
  esac
}

run_timed() {
  local seconds="$1" label="$2" rc
  shift 2
  if timeout --kill-after=30 "$seconds" "$@"; then
    return 0
  else
    rc=$?
    classify_timeout "$rc" "$label"
    return "$rc"
  fi
}

run_compose_timed() {
  local seconds="$1" label="$2" rc
  shift 2
  if (cd "$DEPLOY_DIR" && timeout --kill-after=30 "$seconds" docker compose "$@"); then
    return 0
  else
    rc=$?
    classify_timeout "$rc" "$label"
    return "$rc"
  fi
}

with_lock() {
  if require_command flock; then
    :
  else
    return 1
  fi
  if ! mkdir -p "$STATE_DIR"; then
    echo "상태 디렉터리를 만들 수 없습니다: $STATE_DIR" >&2
    return 1
  fi
  exec 9>"$STATE_DIR/agent.lock"
  if ! flock -n 9; then
    log "배포 또는 관측 동기화가 진행 중이라 이번 주기를 건너뜁니다."
    return 0
  fi
  "$@"
}

notify_suppressed() {
  local reason="$1" tree="$2" first_message="$3" reminder_message="$4"
  local key now last previous message
  key="$reason${tree:+@$tree}"
  now=$(date +%s)
  last=$(state_get '.monitoring_sync.alert.at' 0)
  [[ "$last" =~ ^[0-9]+$ ]] || last=0
  previous=$(state_get '.monitoring_sync.alert.reason')
  if [[ "$previous" == "$key" ]] && ((now - last < REMINDER_INTERVAL_SEC)); then
    log "같은 관측 동기화 실패가 24시간 안에 반복돼 알림을 접습니다."
    return 0
  fi
  if [[ "$previous" == "$key" ]]; then
    message="$reminder_message"
  else
    message="$first_message"
  fi
  if notify "$message"; then
    if hard_state_write '.monitoring_sync.alert = {reason: $reason, at: $at}' \
      --arg reason "$key" --argjson at "$now"; then
      return 0
    fi
  fi
  return 0
}

record_unknown_failure() {
  local reason="$1" first_message="$2" reminder_message="$3"
  if state_init; then
    :
  else
    return 1
  fi
  if hard_state_write '.monitoring_sync.unknown_unresolved = $reason' --arg reason "$reason"; then
    notify_suppressed "$reason" "" "$first_message" "$reminder_message"
  else
    warn "tree를 알 수 없는 실패 상태를 기록하지 못했습니다. 다음 주기에 다시 시도합니다."
  fi
  return 0
}

handle_unknown_failure() {
  local reason="$1" detail="$2"
  if [[ "$DRY_RUN" == true ]]; then
    log "[dry-run] $detail"
    return 0
  fi
  with_lock record_unknown_failure "$reason" \
    "⚠️ 관측 동기화 판정 실패: $detail" \
    "🔔 관측 동기화 판정 실패가 미해소 상태입니다: $detail"
}

clear_unknown_failure() {
  local unresolved
  unresolved=$(state_get '.monitoring_sync.unknown_unresolved')
  [[ -n "$unresolved" ]] || return 0
  if hard_state_write '.monitoring_sync.unknown_unresolved = "" | .monitoring_sync.alert = null'; then
    log "관측 동기화 판정 실패가 해소됐습니다."
    return 0
  fi
  return 1
}

remove_backup() {
  if [[ -z "$MONITORING_DIR" || "$MONITORING_DIR" == "/" || "$BACKUP_DIR" != "$MONITORING_DIR.bak" ]]; then
    warn "안전하지 않은 백업 경로라 삭제를 거부합니다: $BACKUP_DIR"
    return 1
  fi
  [[ -e "$BACKUP_DIR" ]] || return 0
  run_timed 120 "관측 백업 제거" rm -rf -- "$BACKUP_DIR"
}

read_services() {
  local __name="$1" base_file="$2" max_seconds="${3:-30}" temp service
  local -a parsed=()
  if new_temp_file temp; then
    :
  else
    return 1
  fi
  if (cd "$DEPLOY_DIR" && timeout --kill-after=10 "$max_seconds" docker compose -f "$base_file" config --services >"$temp"); then
    while IFS= read -r service; do
      if [[ "$service" == "$API_SERVICE" || "$service" == "$CADDY_SERVICE" ]]; then
        warn "관측 서비스 목록에 보호 서비스가 포함돼 실행을 거부합니다: $service"
        return 1
      fi
      [[ -n "$service" ]] && parsed+=("$service")
    done <"$temp"
  else
    warn "관측 서비스 positive list 산출에 실패했습니다."
    return 1
  fi
  if ((${#parsed[@]} == 0)); then
    warn "관측 서비스 목록이 비어 있어 실행을 거부합니다."
    return 1
  fi
  printf -v "$__name" '%s\n' "${parsed[@]}"
}

services_to_array() {
  local value="$1" __name="$2" item
  local -a result=()
  while IFS= read -r item; do
    [[ -n "$item" ]] && result+=("$item")
  done <<<"$value"
  eval "$__name=(\"\${result[@]}\")"
}

remaining_seconds() {
  local deadline="$1" remaining
  remaining=$((deadline - SECONDS))
  ((remaining > 0)) || return 1
  printf '%s\n' "$remaining"
}

rollback() {
  local new_text="${1:-}" deadline=$((SECONDS + 300)) old_text remaining service candidate
  local old_services_known=true new_services_known=false added_count=0
  local -a old_services=() new_services=() added_services=()

  remaining=$(remaining_seconds "$deadline") || return 1
  if read_services old_text "$BACKUP_DIR/docker-compose.yml" "$remaining"; then
    services_to_array "$old_text" old_services
  else
    old_services_known=false
    warn "구 관측 서비스 목록을 확보하지 못했지만 파일 복원을 계속합니다."
  fi
  if [[ "$old_services_known" == true ]]; then
    if [[ -n "$new_text" ]]; then
      services_to_array "$new_text" new_services
      new_services_known=true
    elif remaining=$(remaining_seconds "$deadline") &&
      read_services new_text "../monitoring/docker-compose.yml" "$remaining"; then
      services_to_array "$new_text" new_services
      new_services_known=true
    else
      warn "새 관측 서비스 목록을 확보하지 못해 신규 서비스 제거를 생략하고 파일 복원을 계속합니다."
    fi

    if [[ "$new_services_known" == true ]]; then
      for candidate in "${new_services[@]}"; do
        local found=false
        for service in "${old_services[@]}"; do
          if [[ "$candidate" == "$service" ]]; then
            found=true
            break
          fi
        done
        if [[ "$found" != true ]]; then
          added_services+=("$candidate")
          added_count=$((added_count + 1))
        fi
      done
    fi

    if ((added_count > 0)); then
      for service in "${added_services[@]}"; do
        if [[ "$service" == "$API_SERVICE" || "$service" == "$CADDY_SERVICE" ]]; then
          warn "복원 제거 집합에 보호 서비스가 포함돼 중단합니다: $service"
          return 1
        fi
      done
      remaining=$(remaining_seconds "$deadline") || return 1
      if run_compose_timed "$remaining" "신규 관측 서비스 중지" stop "${added_services[@]}"; then
        :
      else
        return 1
      fi
      remaining=$(remaining_seconds "$deadline") || return 1
      if run_compose_timed "$remaining" "신규 관측 서비스 제거" rm -f "${added_services[@]}"; then
        :
      else
        return 1
      fi
    fi
  fi

  remaining=$(remaining_seconds "$deadline") || return 1
  if run_timed "$remaining" "관측 설정 복원" rsync -a --checksum --delete --exclude='.env' \
    "$BACKUP_DIR/" "$MONITORING_DIR/"; then
    :
  else
    return 1
  fi
  if [[ "$old_services_known" != true ]]; then
    remaining=$(remaining_seconds "$deadline") || return 2
    if read_services old_text "../monitoring/docker-compose.yml" "$remaining"; then
      services_to_array "$old_text" old_services
      old_services_known=true
    else
      warn "복원된 구 관측 서비스 목록도 확보하지 못해 재기동을 생략합니다."
      return 2
    fi
  fi
  remaining=$(remaining_seconds "$deadline") || return 2
  if run_compose_timed "$remaining" "구 관측 서비스 재기동" up -d --force-recreate "${old_services[@]}"; then
    if [[ "$new_services_known" != true ]]; then
      warn "신규 관측 서비스 제거 여부를 확인하지 못해 부분 복원으로 처리합니다."
      return 2
    fi
    return 0
  fi
  if [[ "$new_services_known" != true ]]; then
    warn "신규 관측 서비스 제거 여부를 확인하지 못한 상태에서 구 서비스 재기동도 실패했습니다."
    return 2
  fi
  return 1
}

critical_restore_failure() {
  local tree="$1"
  warn "관측 스택 복원에 실패했습니다. 관측 스택 down 상태일 수 있어 사람 개입이 필요합니다."
  notify_suppressed "restore-failure" "$tree" \
    "🚨 관측 스택 복원 실패 · 관측 스택 down · 사람 개입 필요 (${tree:0:12})" \
    "🔔 관측 스택 복원 실패가 미해소 상태입니다 · 관측 스택 down · 사람 개입 필요 (${tree:0:12})"
  return 1
}

clear_transaction() {
  hard_state_write '.monitoring_sync.in_progress_tree = ""'
}

recover_incomplete_transaction() {
  local marker="$1" rollback_rc
  log "미완료 관측 동기화 저널을 발견해 먼저 복원합니다(${marker:0:12})."
  if rollback; then
    :
  else
    rollback_rc=$?
    if ((rollback_rc != 2)); then
      critical_restore_failure "$marker"
      return $?
    fi
    finalize_restore_failure "$marker" "incomplete-restore" "중단 복구" true
    return $?
  fi
  finalize_restore_failure "$marker" "recovered-incomplete" "중단된 동기화 복구"
}

record_apply_failure() {
  local tree="$1"
  hard_state_write '
    .monitoring_sync.failure =
      (if (.monitoring_sync.failure.tree // "") == $tree
       then {tree: $tree, count: ((.monitoring_sync.failure.count // 0) + 1)}
       else {tree: $tree, count: 1}
       end)
    | if .monitoring_sync.failure.count >= $threshold
      then .monitoring_sync.failed_trees = (((.monitoring_sync.failed_trees // []) + [$tree]) | unique)
      else .
      end
    | .monitoring_sync.in_progress_tree = ""' \
    --arg tree "$tree" --argjson threshold "$FAILURE_THRESHOLD"
}

brand_tree_after_state_failure() {
  local tree="$1"
  hard_state_write '
    .monitoring_sync.failed_trees = (((.monitoring_sync.failed_trees // []) + [$tree]) | unique)
    | .monitoring_sync.failure = {tree: $tree, count: $threshold}
    | .monitoring_sync.in_progress_tree = ""' \
    --arg tree "$tree" --argjson threshold "$FAILURE_THRESHOLD"
}

is_failed_tree() {
  local tree="$1"
  jq -e --arg tree "$tree" '(.monitoring_sync.failed_trees // []) | index($tree) != null' \
    "$STATE_FILE" >/dev/null 2>&1
}

finalize_restore_failure() {
  local tree="$1" reason="$2" detail="$3" partial_restore="${4:-false}" count
  if record_apply_failure "$tree"; then
    count=$(state_get '.monitoring_sync.failure.count' 0)
    [[ "$count" =~ ^[0-9]+$ ]] || count=0
    if is_failed_tree "$tree"; then
      if [[ "$partial_restore" == true ]]; then
        notify_suppressed "failed-tree" "$tree" \
          "⚠️ 관측 동기화가 3회 부분 복원에 그쳐 이 트리를 낙인 처리했습니다 (${tree:0:12})" \
          "🔔 부분 복원 후 낙인된 관측 트리가 미해소 상태입니다 (${tree:0:12})"
      else
        notify_suppressed "failed-tree" "$tree" \
          "⚠️ 관측 동기화를 3회 실패해 이 트리를 낙인 처리했습니다 (${tree:0:12})" \
          "🔔 낙인된 관측 트리가 미해소 상태입니다 (${tree:0:12})"
      fi
    elif [[ "$partial_restore" == true ]]; then
      notify_suppressed "partial-$reason" "$tree" \
        "⚠️ 관측 동기화 실패 후 부분 복원했습니다: $detail (${count}/${FAILURE_THRESHOLD}, ${tree:0:12})" \
        "🔔 관측 동기화의 부분 복원이 미해소 상태입니다: $detail (${tree:0:12})"
    else
      notify_suppressed "$reason" "$tree" \
        "⚠️ 관측 동기화 실패 후 복원했습니다: $detail (${count}/${FAILURE_THRESHOLD}, ${tree:0:12})" \
        "🔔 관측 동기화 실패가 미해소 상태입니다: $detail (${tree:0:12})"
    fi
    if ! remove_backup; then
      warn "복원과 실패 기록은 끝났지만 백업 저널 제거에 실패했습니다. 다음 주기에 정리합니다."
    fi
    return 0
  fi
  warn "복원 후 실패 카운터 기록에 실패해 이 트리를 즉시 낙인 처리합니다."
  if brand_tree_after_state_failure "$tree"; then
    notify_suppressed "failed-tree" "$tree" \
      "⚠️ 실패 카운터 기록 장애로 관측 트리를 즉시 낙인 처리했습니다 (${tree:0:12})" \
      "🔔 낙인된 관측 트리가 미해소 상태입니다 (${tree:0:12})"
    if ! remove_backup; then
      warn "낙인은 기록했지만 백업 저널 제거에 실패했습니다. 다음 주기에 정리합니다."
    fi
  else
    warn "낙인 기록도 실패해 재생성 반복을 막도록 미완료 저널을 보존합니다."
  fi
  return 0
}

handle_apply_failure() {
  local tree="$1" reason="$2" detail="$3" services_text="${4:-}" rollback_rc partial_restore=false
  if rollback "$services_text"; then
    :
  else
    rollback_rc=$?
    if ((rollback_rc != 2)); then
      critical_restore_failure "$tree"
      return $?
    fi
    partial_restore=true
  fi
  finalize_restore_failure "$tree" "$reason" "$detail" "$partial_restore"
}

archive_monitoring() {
  local rev="$1" target="$2" rc
  if timeout --kill-after=30 120 bash -c '
    set -o pipefail
    git -C "$1" archive "$2" monitoring | tar -x -C "$3"
    status=("${PIPESTATUS[@]}")
    ((status[0] == 0 && status[1] == 0))
  ' _ "$REPO_DIR" "$rev" "$target"; then
    return 0
  else
    rc=$?
    classify_timeout "$rc" "monitoring git archive|tar"
    return "$rc"
  fi
}

finalize_without_rollback() {
  local tree="$1" reason="$2" detail="$3"
  if clear_transaction; then
    notify_suppressed "$reason" "$tree" \
      "⚠️ 관측 동기화를 보류했습니다: $detail (${tree:0:12})" \
      "🔔 관측 동기화 보류가 미해소 상태입니다: $detail (${tree:0:12})"
    if ! remove_backup; then
      warn "복원 없는 종료의 백업 저널 제거에 실패했습니다. 다음 주기에 정리합니다."
    fi
    return 0
  fi
  warn "복원 없는 종료의 저널 정리에 실패해 구 설정으로 복원합니다."
  handle_apply_failure "$tree" "journal-finalize" "저널 정리 실패"
}

service_state_ok() {
  local service="$1" id state
  id=$(cd "$DEPLOY_DIR" && docker compose ps -q "$service" 2>/dev/null) || return 1
  [[ -n "$id" ]] || return 1
  state=$(docker inspect "$id" --format '{{.State.Status}} {{.State.Restarting}}' 2>/dev/null) || return 1
  [[ "$state" == "running false" ]]
}

observe_services() {
  local services_text="$1" elapsed=0 consecutive=0 service all_ok
  local -a services=()
  services_to_array "$services_text" services
  while ((elapsed <= SETTLE_SEC)); do
    all_ok=true
    for service in "${services[@]}"; do
      if service_state_ok "$service"; then
        :
      else
        all_ok=false
        warn "관측 서비스 상태가 안정적이지 않습니다: $service"
      fi
    done
    if [[ "$all_ok" == true ]]; then
      consecutive=$((consecutive + 1))
    else
      consecutive=0
    fi
    if ((elapsed >= SETTLE_SEC)); then
      break
    fi
    sleep "$HEALTH_POLL_SEC"
    elapsed=$((elapsed + HEALTH_POLL_SEC))
  done
  ((elapsed >= SETTLE_SEC && consecutive >= 2))
}

soft_smoke() {
  local prometheus grafana unhealthy
  local -a failures=()
  if prometheus=$(curl -fsS --max-time 15 http://127.0.0.1:9090/api/v1/targets 2>/dev/null); then
    unhealthy=$(jq -r 'if .status == "success" then [.data.activeTargets[]? | select(.health != "up")] | length else -1 end' \
      <<<"$prometheus" 2>/dev/null) || unhealthy=-1
    [[ "$unhealthy" == "0" ]] || failures+=("Prometheus targets=$unhealthy")
  else
    failures+=("Prometheus 연결 실패")
  fi
  if grafana=$(curl -fsS --max-time 15 http://127.0.0.1:3000/api/health 2>/dev/null); then
    if jq -e '.database == "ok"' <<<"$grafana" >/dev/null 2>&1; then
      :
    else
      failures+=("Grafana health 불일치")
    fi
  else
    failures+=("Grafana 연결 실패")
  fi
  SMOKE_DETAIL="${failures[*]:-}"
  ((${#failures[@]} == 0))
}

write_success_state() {
  local tree="$1" smoke="$2"
  hard_state_write '
    .monitoring_sync.synced_tree = $tree
    | .monitoring_sync.failure = {tree: "", count: 0}
    | .monitoring_sync.in_progress_tree = ""
    | .monitoring_sync.unknown_unresolved = ""
    | .monitoring_sync.smoke_unresolved = $smoke
    | .monitoring_sync.alert = null' \
    --arg tree "$tree" --arg smoke "$smoke"
}

recheck_smoke() {
  local tree="$1"
  if soft_smoke; then
    if hard_state_write '.monitoring_sync.smoke_unresolved = "" | .monitoring_sync.alert = null'; then
      if notify "✅ 관측 스택 소프트 스모크가 복구됐습니다 (${tree:0:12})"; then
        :
      else
        :
      fi
    else
      warn "스모크 복구 상태 기록에 실패했습니다. 다음 주기에 다시 검사합니다."
    fi
  else
    if hard_state_write '.monitoring_sync.smoke_unresolved = $detail' --arg detail "$SMOKE_DETAIL"; then
      notify_suppressed "smoke-unresolved" "$tree" \
        "⚠️ 관측 스택 소프트 스모크 실패: $SMOKE_DETAIL (${tree:0:12})" \
        "🔔 관측 스택 소프트 스모크가 미해소 상태입니다: $SMOKE_DETAIL (${tree:0:12})"
    else
      warn "스모크 미해소 상태 기록에 실패했습니다."
    fi
  fi
  return 0
}

retry_failed() {
  if state_init; then
    :
  else
    return 1
  fi
  if hard_state_write '
    .monitoring_sync.failed_trees = []
    | .monitoring_sync.failure = {tree: "", count: 0}
    | .monitoring_sync.in_progress_tree = ""
    | .monitoring_sync.alert = null'; then
    log "관측 동기화 실패 기록과 카운터, 미완료 marker를 비웠습니다."
    return 0
  fi
  return 1
}

locked_cycle() {
  local rev="$1" tree="$2" current_rev current_tree synced smoke marker archive_dir services_text
  local -a services=()
  if state_init; then
    :
  else
    return 1
  fi

  marker=$(state_get '.monitoring_sync.in_progress_tree')
  if [[ -e "$BACKUP_DIR" ]]; then
    if [[ -n "$marker" ]]; then
      if recover_incomplete_transaction "$marker"; then
        return 0
      fi
      return 1
    fi
    if remove_backup; then
      :
    else
      warn "잔존 백업 저널 제거에 실패했습니다. 다음 주기에 다시 정리합니다."
      return 0
    fi
  elif [[ -n "$marker" ]]; then
    warn "미완료 marker가 있지만 백업 저널이 없어 처음부터 다시 적용합니다(${marker:0:12})."
    if record_apply_failure "$marker"; then
      if is_failed_tree "$marker"; then
        notify_suppressed "failed-tree" "$marker" \
          "⚠️ 백업 저널 없는 미완료 marker가 3회 발생해 이 트리를 낙인 처리했습니다 (${marker:0:12})" \
          "🔔 낙인된 관측 트리가 미해소 상태입니다 (${marker:0:12})"
      else
        notify_suppressed "missing-backup" "$marker" \
          "⚠️ 미완료 관측 동기화 marker의 백업 저널이 없어 처음부터 다시 적용합니다 (${marker:0:12})" \
          "🔔 백업 저널 없는 미완료 관측 동기화 marker가 다시 발견됐습니다 (${marker:0:12})"
      fi
    else
      warn "백업 저널 없는 marker의 실패 카운터 기록에 실패해 이 트리를 즉시 낙인 처리합니다."
      if brand_tree_after_state_failure "$marker"; then
        notify_suppressed "failed-tree" "$marker" \
          "⚠️ 실패 카운터 기록 장애로 관측 트리를 즉시 낙인 처리했습니다 (${marker:0:12})" \
          "🔔 낙인된 관측 트리가 미해소 상태입니다 (${marker:0:12})"
      else
        warn "낙인 기록도 실패해 미완료 marker를 보존합니다. --retry로 수동 초기화할 수 있습니다."
      fi
    fi
    return 0
  fi

  if clear_unknown_failure; then
    :
  else
    warn "해소된 판정 실패 상태를 지우지 못해 이번 주기를 건너뜁니다."
    return 0
  fi

  synced=$(state_get '.monitoring_sync.synced_tree')
  smoke=$(state_get '.monitoring_sync.smoke_unresolved')
  if [[ "$RESYNC" != true && "$tree" == "$synced" ]]; then
    if [[ -n "$smoke" ]]; then
      recheck_smoke "$tree"
    else
      log "관측 트리가 이미 동기화돼 있습니다(${tree:0:12})."
    fi
    return 0
  fi
  if is_failed_tree "$tree"; then
    notify_suppressed "failed-tree" "$tree" \
      "⚠️ 낙인된 관측 트리라 동기화를 건너뜁니다 (${tree:0:12})" \
      "🔔 낙인된 관측 트리가 미해소 상태입니다 (${tree:0:12})"
    return 0
  fi

  current_rev=$(container_revision)
  if [[ "$current_rev" != "$rev" ]]; then
    log "락 대기 중 api revision이 바뀌어 다음 주기로 미룹니다."
    return 0
  fi
  current_tree=$(git -C "$REPO_DIR" rev-parse "$rev:monitoring" 2>/dev/null) || current_tree=""
  if [[ ! "$current_tree" =~ ^[0-9a-f]{40}$ || "$current_tree" != "$tree" ]]; then
    log "락 안 재검증에서 monitoring 트리가 달라져 다음 주기로 미룹니다."
    return 0
  fi

  if [[ ! -d "$MONITORING_DIR" ]]; then
    warn "관측 디렉터리가 없습니다: $MONITORING_DIR"
    notify_suppressed "monitoring-dir-missing" "$tree" \
      "⚠️ 관측 동기화 대상 디렉터리가 없습니다 (${tree:0:12})" \
      "🔔 관측 동기화 대상 디렉터리 부재가 미해소 상태입니다 (${tree:0:12})"
    return 0
  fi
  if run_timed 120 "관측 설정 백업" cp -a "$MONITORING_DIR" "$BACKUP_DIR"; then
    :
  else
    notify_suppressed "backup" "$tree" \
      "⚠️ 관측 설정 백업에 실패했습니다 (${tree:0:12})" \
      "🔔 관측 설정 백업 실패가 미해소 상태입니다 (${tree:0:12})"
    return 0
  fi
  if hard_state_write '.monitoring_sync.in_progress_tree = $tree' --arg tree "$tree"; then
    :
  else
    if remove_backup; then
      :
    else
      warn "marker 기록 실패 뒤 백업 정리에도 실패했습니다. 다음 주기가 저널을 판정합니다."
    fi
    return 0
  fi

  if new_temp_dir archive_dir; then
    :
  else
    finalize_without_rollback "$tree" "archive-temp" "archive 임시 디렉터리 생성 실패"
    return $?
  fi
  if archive_monitoring "$rev" "$archive_dir"; then
    :
  else
    finalize_without_rollback "$tree" "archive" "monitoring archive 추출 실패"
    return $?
  fi

  if run_timed 120 "관측 설정 rsync" rsync -a --checksum --delete --exclude='.env' \
    "$archive_dir/monitoring/" "$MONITORING_DIR/"; then
    :
  else
    if handle_apply_failure "$tree" "rsync" "설정 파일 동기화 실패"; then return 0; else return 1; fi
  fi
  if run_compose_timed 30 "관측 compose 검증" config -q; then
    :
  else
    if handle_apply_failure "$tree" "compose-config" "compose config 검증 실패"; then return 0; else return 1; fi
  fi
  if read_services services_text "../monitoring/docker-compose.yml"; then
    services_to_array "$services_text" services
  else
    if handle_apply_failure "$tree" "service-list" "positive service 목록 산출 실패"; then return 0; else return 1; fi
  fi

  if run_compose_timed 600 "관측 이미지 pull" pull "${services[@]}"; then
    :
  else
    finalize_without_rollback "$tree" "pull" "관측 이미지 pull 실패"
    return $?
  fi
  if run_compose_timed 300 "관측 서비스 재생성" up -d --force-recreate "${services[@]}"; then
    :
  else
    if handle_apply_failure "$tree" "compose-up" "관측 서비스 재생성 실패" "$services_text"; then return 0; else return 1; fi
  fi
  if observe_services "$services_text"; then
    :
  else
    if handle_apply_failure "$tree" "container-state" "60초 정착 후 컨테이너 상태 불안정" "$services_text"; then return 0; else return 1; fi
  fi

  if soft_smoke; then
    smoke=""
  else
    smoke="$SMOKE_DETAIL"
  fi
  if write_success_state "$tree" "$smoke"; then
    if remove_backup; then
      if [[ -z "$smoke" ]]; then
        if notify "✅ 관측 스택 동기화 완료 (${tree:0:12})"; then
          :
        else
          :
        fi
      else
        notify_suppressed "smoke-unresolved" "$tree" \
          "⚠️ 관측 설정은 동기화됐지만 소프트 스모크가 실패했습니다: $smoke (${tree:0:12})" \
          "🔔 관측 스택 소프트 스모크가 미해소 상태입니다: $smoke (${tree:0:12})"
      fi
      return 0
    fi
    warn "동기화 상태는 확정됐지만 백업 저널 제거에 실패했습니다. 다음 주기에 정리합니다."
    return 0
  fi
  if handle_apply_failure "$tree" "state-write" "동기화 완료 상태 기록 실패" "$services_text"; then return 0; else return 1; fi
}

run_cycle() {
  local rev tree rc
  rev=$(container_revision)
  if [[ ! "$rev" =~ ^[0-9a-f]{40}$ ]]; then
    log "api 컨테이너 revision 라벨이 없거나 올바른 40hex가 아니라 건너뜁니다."
    return 0
  fi

  if run_timed 60 "git fetch" git -C "$REPO_DIR" fetch origin main; then
    :
  else
    handle_unknown_failure "git-fetch" "git fetch 실패"
    return 0
  fi
  tree=$(git -C "$REPO_DIR" rev-parse "$rev:monitoring" 2>/dev/null) || tree=""
  if [[ ! "$tree" =~ ^[0-9a-f]{40}$ ]]; then
    handle_unknown_failure "tree-lookup" "승격 revision의 monitoring tree 획득 실패"
    return 0
  fi
  log "승격 revision의 관측 트리: ${tree:0:12}"

  if [[ "$DRY_RUN" == true ]]; then
    log "[dry-run] 판정 완료. docker·monitoring/·상태 파일은 변경하지 않았습니다(git fetch만 예외)."
    return 0
  fi
  with_lock locked_cycle "$rev" "$tree"
}

self_test_setup() {
  local name="$1"
  STATE_DIR="$SELF_TEST_ROOT/$name/state"
  STATE_FILE="$STATE_DIR/state.json"
  DEPLOY_DIR="$SELF_TEST_ROOT/$name/deploy"
  MONITORING_DIR="$SELF_TEST_ROOT/$name/monitoring"
  BACKUP_DIR="$MONITORING_DIR.bak"
  SELF_TEST_EVENTS="$SELF_TEST_ROOT/$name/events"
  # 케이스는 서브셸로 돌아 부모의 trap cleanup을 상속하지 않는다(TEMP_* 등록도 서브셸 로컬).
  # TMPDIR을 케이스 밑으로 옮겨야 new_temp_file·new_temp_dir 산출물이 SELF_TEST_ROOT 안에 남아 마지막 rm -rf로 회수된다.
  TMPDIR="$SELF_TEST_ROOT/$name/tmp"
  # archive_monitoring은 자식 bash를 띄워 git 함수 스텁이 무효다 — 실제 리포 대신 빈 디렉터리를 보게 한다.
  REPO_DIR="$SELF_TEST_ROOT/$name/repo"
  TEMP_FILES=()
  TEMP_DIRS=()
  mkdir -p "$STATE_DIR" "$DEPLOY_DIR" "$MONITORING_DIR" "$TMPDIR" "$REPO_DIR"
  printf '{}\n' >"$STATE_FILE"
  : >"$SELF_TEST_EVENTS"
}

self_test_state_count_is() {
  local expected="$1"
  [[ "$(jq -r '.monitoring_sync.failure.count // 0' "$STATE_FILE")" == "$expected" ]]
}

self_test_tree_sha() {
  self_test_setup tree-sha
  SELF_TEST_REV=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
  SELF_TEST_TREE=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
  container_revision() { printf '%s\n' "$SELF_TEST_REV"; }
  git() {
    if [[ "${3:-}" == fetch ]]; then
      return 0
    fi
    if [[ "${3:-}" == rev-parse && "${4:-}" == "$SELF_TEST_REV:monitoring" ]]; then
      printf '%s\n' "$SELF_TEST_TREE"
      return 0
    fi
    if [[ "${3:-}" == rev-parse && "${4:-}" == "$SELF_TEST_REV:monitoring^{tree}" ]]; then
      return 128
    fi
    return 1
  }
  with_lock() { printf '%s\t%s\t%s\n' "$1" "$2" "$3" >>"$SELF_TEST_EVENTS"; }
  run_cycle >/dev/null
  [[ "$(<"$SELF_TEST_EVENTS")" == $'locked_cycle\t'"$SELF_TEST_REV"$'\t'"$SELF_TEST_TREE" ]]
}

self_test_recover_partial_rc() {
  self_test_setup recover-partial
  local tree=1111111111111111111111111111111111111111 rc
  rollback() { return 2; }
  critical_restore_failure() { printf 'critical\n' >>"$SELF_TEST_EVENTS"; return 1; }
  remove_backup() { return 0; }
  notify_suppressed() { return 0; }
  if recover_incomplete_transaction "$tree" >/dev/null 2>&1; then rc=0; else rc=$?; fi
  [[ "$rc" == 0 && ! -s "$SELF_TEST_EVENTS" ]] && self_test_state_count_is 1
}

self_test_apply_partial_rc() {
  self_test_setup apply-partial
  local tree=2222222222222222222222222222222222222222 rc
  rollback() { return 2; }
  critical_restore_failure() { printf 'critical\n' >>"$SELF_TEST_EVENTS"; return 1; }
  remove_backup() { return 0; }
  notify_suppressed() { return 0; }
  if handle_apply_failure "$tree" test test >/dev/null 2>&1; then rc=0; else rc=$?; fi
  [[ "$rc" == 0 && ! -s "$SELF_TEST_EVENTS" ]] && self_test_state_count_is 1
}

self_test_restore_failure_rc() {
  self_test_setup restore-failure
  local tree=3333333333333333333333333333333333333333 rc
  rollback() { return 1; }
  critical_restore_failure() { printf 'critical\n' >>"$SELF_TEST_EVENTS"; return 1; }
  if handle_apply_failure "$tree" test test >/dev/null 2>&1; then rc=0; else rc=$?; fi
  [[ "$rc" == 1 && -s "$SELF_TEST_EVENTS" ]]
}

self_test_pull_no_rollback() {
  self_test_setup pull-no-rollback
  local rev=4444444444444444444444444444444444444444
  local tree=5555555555555555555555555555555555555555 rc
  container_revision() { printf '%s\n' "$rev"; }
  git() { [[ "${3:-}" == rev-parse ]] && printf '%s\n' "$tree"; }
  run_timed() { return 0; }
  archive_monitoring() { return 0; }
  read_services() { printf -v "$1" 'prometheus\n'; }
  run_compose_timed() {
    if [[ " $* " == *" pull "* ]]; then return 1; fi
    return 0
  }
  rollback() { printf 'rollback\n' >>"$SELF_TEST_EVENTS"; return 0; }
  remove_backup() { return 0; }
  notify_suppressed() { printf 'notify\n' >>"$SELF_TEST_EVENTS"; return 0; }
  if locked_cycle "$rev" "$tree" >/dev/null 2>&1; then rc=0; else rc=$?; fi
  [[ "$rc" == 0 && "$(<"$SELF_TEST_EVENTS")" == notify ]]
}

self_test_backup_remove_warning() {
  self_test_setup backup-remove-warning
  local tree=6666666666666666666666666666666666666666 rc first second lines=0
  clear_transaction() { return 0; }
  remove_backup() { return 1; }
  notify_suppressed() { printf '%s\n' "$1" >>"$SELF_TEST_EVENTS"; return 0; }
  if finalize_without_rollback "$tree" pull pull >/dev/null 2>&1; then first=0; else first=$?; fi
  if finalize_restore_failure "$tree" apply apply >/dev/null 2>&1; then second=0; else second=$?; fi
  while IFS= read -r _; do lines=$((lines + 1)); done <"$SELF_TEST_EVENTS"
  rc=$((first + second))
  [[ "$rc" == 0 && "$lines" == 2 ]]
}

self_test_missing_backup_counter() {
  self_test_setup missing-backup-counter
  local tree=7777777777777777777777777777777777777777 i
  notify_suppressed() { return 0; }
  for i in 1 2 3; do
    : "$i"
    hard_state_write '.monitoring_sync.in_progress_tree = $tree' --arg tree "$tree"
    locked_cycle ignored ignored >/dev/null 2>&1
  done
  self_test_state_count_is 3 && is_failed_tree "$tree"
}

self_test_recovery_counter() {
  self_test_setup recovery-counter
  local tree=8888888888888888888888888888888888888888 i
  rollback() { return 0; }
  remove_backup() { return 0; }
  notify_suppressed() { return 0; }
  for i in 1 2 3; do
    : "$i"
    recover_incomplete_transaction "$tree" >/dev/null 2>&1
  done
  self_test_state_count_is 3 && is_failed_tree "$tree"
}

self_test_reparse_restart_partial() {
  self_test_setup reparse-restart-partial
  local calls=0 rc value
  timeout() {
    printf '%s\n' "$2" >>"$SELF_TEST_EVENTS"
    shift 2
    "$@"
  }
  SELF_TEST_DOCKER_SERVICES=prometheus
  read_services value ../monitoring/docker-compose.yml 7 >/dev/null 2>&1
  [[ "$(<"$SELF_TEST_EVENTS")" == 7 ]] || return 1
  : >"$SELF_TEST_EVENTS"
  read_services() {
    calls=$((calls + 1))
    [[ -n "${3:-}" ]] || return 1
    if ((calls == 1)); then return 1; fi
    printf -v "$1" 'prometheus\n'
  }
  run_timed() { return 0; }
  run_compose_timed() { printf '%s\n' "$*" >>"$SELF_TEST_EVENTS"; return 1; }
  if rollback >/dev/null 2>&1; then rc=0; else rc=$?; fi
  [[ "$rc" == 2 && "$(<"$SELF_TEST_EVENTS")" == *"up -d --force-recreate prometheus"* ]] || return 1

  calls=0
  : >"$SELF_TEST_EVENTS"
  read_services() { printf -v "$1" 'prometheus\n'; }
  remaining_seconds() {
    local seen=0
    while IFS= read -r _; do seen=$((seen + 1)); done <"$SELF_TEST_EVENTS"
    printf 'remaining\n' >>"$SELF_TEST_EVENTS"
    ((seen < 3)) || return 1
    printf '10\n'
  }
  run_compose_timed() { : >"$SELF_TEST_ROOT/unexpected-up"; return 0; }
  if rollback >/dev/null 2>&1; then rc=0; else rc=$?; fi
  [[ "$rc" == 2 && ! -e "$SELF_TEST_ROOT/unexpected-up" ]]
}

self_test_exit_contract() {
  self_test_setup exit-contract
  local tree=9999999999999999999999999999999999999999 recoverable critical
  DRY_RUN=true
  if handle_unknown_failure test test >/dev/null 2>&1; then recoverable=0; else recoverable=$?; fi
  DRY_RUN=false
  rollback() { return "${SELF_TEST_ROLLBACK_RC:-2}"; }
  remove_backup() { return 0; }
  notify_suppressed() { return 0; }
  critical_restore_failure() { return 1; }
  SELF_TEST_ROLLBACK_RC=2
  handle_apply_failure "$tree" test test >/dev/null 2>&1 || recoverable=$((recoverable + $?))
  SELF_TEST_ROLLBACK_RC=1
  if handle_apply_failure "$tree" test test >/dev/null 2>&1; then critical=0; else critical=$?; fi
  [[ "$recoverable" == 0 && "$critical" == 1 ]]
}

self_test_protected_services() {
  self_test_setup protected-services
  local protected value rc
  for protected in "$API_SERVICE" "$CADDY_SERVICE"; do
    SELF_TEST_DOCKER_SERVICES="$protected"
    if read_services value ../monitoring/docker-compose.yml >/dev/null 2>&1; then return 1; fi
    SELF_TEST_DOCKER_SERVICES=prometheus
    run_timed() { printf 'rsync\n' >>"$SELF_TEST_EVENTS"; return 0; }
    if rollback "$protected" >/dev/null 2>&1; then rc=0; else rc=$?; fi
    [[ "$rc" == 1 && ! -s "$SELF_TEST_EVENTS" ]] || return 1
  done
}

self_test_idempotent() {
  self_test_setup idempotent
  local tree=abcdefabcdefabcdefabcdefabcdefabcdefabcd
  hard_state_write '.monitoring_sync.synced_tree = $tree' --arg tree "$tree"
  container_revision() { printf 'called\n' >>"$SELF_TEST_EVENTS"; }
  run_timed() { printf 'called\n' >>"$SELF_TEST_EVENTS"; return 1; }
  rollback() { printf 'called\n' >>"$SELF_TEST_EVENTS"; return 1; }
  locked_cycle ignored "$tree" >/dev/null 2>&1
  [[ ! -s "$SELF_TEST_EVENTS" ]]
}

self_test_run_case() {
  local name="$1" function_name="$2"
  if ("$function_name"); then
    printf 'PASS %s\n' "$name"
  else
    printf 'FAIL %s\n' "$name"
    SELF_TEST_FAILURES=$((SELF_TEST_FAILURES + 1))
  fi
}

self_test() {
  # 조용한 SKIP은 검증 게이트를 거짓 통과시킨다(0건 실행에도 green). 운영 경로와 같이 하드 요구한다.
  if require_command jq; then :; else return 1; fi

  SELF_TEST_ROOT=$(mktemp -d "${TMPDIR:-/tmp}/woni-monitoring-sync-self-test.XXXXXX") || return 1
  TEMP_DIRS+=("$SELF_TEST_ROOT")
  SELF_TEST_FAILURES=0
  SELF_TEST_DOCKER_SERVICES=prometheus
  docker() {
    if [[ " $* " == *" config --services "* ]]; then
      printf '%s\n' "$SELF_TEST_DOCKER_SERVICES"
    fi
    return 0
  }
  git() { return 0; }
  rsync() { return 0; }
  tar() { return 0; }
  timeout() {
    while [[ "${1:-}" == --kill-after=* ]]; do shift; done
    shift
    "$@"
  }
  curl() { return 0; }
  flock() { return 0; }

  self_test_run_case '트리 sha 산출' self_test_tree_sha
  self_test_run_case 'rc=2 전파(recover)' self_test_recover_partial_rc
  self_test_run_case 'rc=2 전파(apply)' self_test_apply_partial_rc
  self_test_run_case 'rc=1 critical 승격' self_test_restore_failure_rc
  self_test_run_case 'pull 실패 무복원' self_test_pull_no_rollback
  self_test_run_case 'backup 제거 실패 알림' self_test_backup_remove_warning
  self_test_run_case 'marker 자가 치유 카운터' self_test_missing_backup_counter
  self_test_run_case '저널 복구 카운터' self_test_recovery_counter
  self_test_run_case '복원 config 재파싱' self_test_reparse_restart_partial
  self_test_run_case 'D8 종료 코드' self_test_exit_contract
  self_test_run_case '보호 서비스 거부' self_test_protected_services
  self_test_run_case '멱등' self_test_idempotent

  rm -rf -- "$SELF_TEST_ROOT"
  ((SELF_TEST_FAILURES == 0))
}

main() {
  local mode=cycle
  case "${1:-}" in
    "") ;;
    --dry-run)
      mode=dry-run
      DRY_RUN=true
      ;;
    --retry) mode=retry ;;
    --resync)
      RESYNC=true
      ;;
    --self-test) mode=self-test ;;
    -h | --help)
      usage
      return 0
      ;;
    *)
      echo "알 수 없는 플래그: $1" >&2
      usage >&2
      return 1
      ;;
  esac
  (($# <= 1)) || {
    usage >&2
    return 1
  }

  if [[ "$mode" == self-test ]]; then
    self_test
    return $?
  fi

  if [[ ! -e "$REPO_DIR/.git" ]]; then
    log "git 저장소가 아니라 관측 동기화를 건너뜁니다: $REPO_DIR"
    return 0
  fi
  if load_config; then :; else return 1; fi
  if require_command jq; then :; else return 1; fi
  if require_command curl; then :; else return 1; fi

  if [[ "$mode" == retry ]]; then
    with_lock retry_failed
    return $?
  fi

  if require_command git; then :; else return 1; fi
  if require_command docker; then :; else return 1; fi
  if require_command rsync; then :; else return 1; fi
  if require_command tar; then :; else return 1; fi
  if ! command -v timeout >/dev/null 2>&1; then
    if [[ "$DRY_RUN" == true ]]; then
      log "[dry-run] GNU timeout이 없어 macOS 로컬 판정을 여기서 정상 종료합니다."
      return 0
    fi
    if require_command timeout; then :; else return 1; fi
  fi
  run_cycle
}

trap cleanup EXIT
main "$@"

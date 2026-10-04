#!/usr/bin/env bash
# woni 배포 에이전트 — GHCR 의 :deploy 태그를 폴링해 두 색(api-blue·api-green) 중 쉬는 색에 배포하고, 직접 스모크
# 뒤 그 색의 게이트를 열어 투입한 다음 살아 있던 색을 빼고 멈춘다(무중단 전환).
# 설계 SSOT 는 .claude/plan/cicd-deploy-plan.md §4 와 .claude/plans/zero-downtime-deploy/DESIGN.md §4 다.
# systemd timer(300초)가 인자 없이 호출한다.
# Linux 호스트 전용이다(flock). macOS 에서는 bash -n·shellcheck·--dry-run·--self-test 만 돈다.
#
# 라우팅 레버는 컨테이너 안 게이트 파일 하나뿐이다 — 앱 readiness 그룹의 deployGate 가 그 파일을 보고, Caddy 는
# 정적 upstream 둘의 readiness 를 본다. Caddyfile 은 사람 소유라 이 스크립트는 Caddy 설정을 쓰지도, admin API 나
# reload 를 부르지도 않는다(ADR-016 신뢰 경계). compose 호출은 언제나 서비스 하나를 지정하고 up 에는 --no-deps 를
# 붙인다 — 빠지면 두 색과 caddy 를 함께 재생성·기동해 살아 있는 색을 건드린다.
# 직전 이미지 보존은 멈춘 옛 색 컨테이너가 맡는다(docker image prune -f 는 컨테이너가 참조하는 이미지를 지우지 않는다).
#
# 알림 억제(플랜 §9 에서 미정으로 남긴 항목 — 여기서 확정): 미해소 상태를 종류별 채널로 나누지 않고
# **일 1회**로 묶는다(m9). ① 낙인·그룹 B 미해소는 stale_reminder_at 으로 요약 1건 ② 하드 실패(디스크·
# override 교체·pull·기동/스모크 실패·두 색 모두 열림·서비스 불능·판정 불가)는 사람이 고칠 때까지 300초마다
# 그대로 재발하므로 hard_alert 로 같은 사유당 1일 1건 — 사유가 바뀌면 즉시 울리고, 성공 배포가 hard_alert 를 비워 조건이 해소되면
# 다음 발생 때 다시 울린다(live/ 사유는 열린 색이 정확히 하나로 돌아온 주기가 비운다). 사유키에는 digest 와 낙인 여부를 함께 넣어 새 이미지의 첫 실패와 낙인
# 전환이 접히지 않게 한다. 나머지는 조회 실패 3회 연속(=15분)에서 1회만, 보류는 참조당 1회.

# jq 필터의 $d·$v 는 --arg 로 넘기는 jq 변수이고, docker exec 의 sh -c 스크립트 속 $WONI_DEPLOY_GATE_FILE 은
# 컨테이너 안 sh 가 확장할 변수라 셸이 확장하면 안 된다(SC2016 은 이 파일에서 전부 그 경우다).
# shellcheck disable=SC2016

set -euo pipefail

readonly IMAGE_PATH="leejungkuk/multi_currency_household_ledger"
readonly IMAGE_REPO="ghcr.io/$IMAGE_PATH"
readonly DEPLOY_TAG="deploy"
# 색 c 의 compose 서비스는 api-c, 컨테이너는 woni-api-c 다(리포 밖 deploy/docker-compose.yml 과 같은 이름).
readonly CADDY_CONTAINER="woni-caddy"
# Dockerfile HEALTHCHECK 가 start-period=120s interval=30s retries=3 이라 unhealthy 확정까지 최악 210초이고,
# JWKS 콜드 스타트로 1회 재시작하면 start-period 가 다시 돈다.
readonly HEALTH_TIMEOUT_SEC=420
readonly HEALTH_POLL_SEC=10
# 게이트를 여닫은 뒤 Caddy 가 그 색을 투입·철수할 때까지 기다리는 시간. 계약(DESIGN §3): Caddyfile 의
# health_interval 2s + health_timeout 1s + 여유 이상이어야 한다 — 한쪽을 바꾸면 다른 쪽도 바꾼다.
readonly GATE_SETTLE_SEC=5
# 환율 수집 창(ExchangeRateScheduler: 11:05 daily · 11~14 인트라데이 · 14:00 cutoff)을 밟지 않는다.
readonly HOLD_FROM=1040
readonly HOLD_TO=1405
readonly DISK_MIN_MB=3072
readonly PROBE_FAILURE_THRESHOLD=3
readonly REMINDER_INTERVAL_SEC=86400
readonly SMOKE_BODY_BYTES=3000000 # Caddyfile 의 request_body max_size 2MB 초과분

STATE_DIR="${STATE_DIRECTORY:-/var/lib/woni-deploy}"
STATE_FILE="$STATE_DIR/state.json"
DRY_RUN=false
GROUPB_LABELS=""
TEMPS=()

log() { printf '%s %s\n' "$(TZ=Asia/Seoul date '+%F %T%z')" "$*"; }
warn() { log "$*" >&2; }

cleanup() {
  ((${#TEMPS[@]} > 0)) && rm -f "${TEMPS[@]}"
  return 0
}

# $1=경로를 담을 변수명, $2=디렉터리(기본 TMPDIR). 값을 표준출력으로 돌려주면 호출자가 명령치환
# 서브셸이 되어 TEMPS 등록이 사라지고 EXIT 정리가 아무것도 지우지 못한다.
new_temp() {
  # 내부 변수명에 __ 를 붙인다 — 호출자가 넘긴 이름과 같으면 local 이 그 이름을 가려 빈 값이 돌아간다.
  local __dir="${2:-${TMPDIR:-/tmp}}" __path
  __path=$(mktemp "${__dir%/}/.woni-deploy.XXXXXX") || return 1
  TEMPS+=("$__path")
  printf -v "$1" '%s' "$__path"
}

usage() {
  cat <<'EOF'
사용법: tools/deploy-agent.sh [모드]
  (없음)                     1회 판정·배포 (systemd 가 호출)
  --once                     사람이 손으로 즉시 1회 (동작은 기본과 동일)
  --dry-run                  판정까지만(살아 있는 색·새 색·판정표의 행). docker·ntfy·상태파일에 쓰지 않는다
  --clear-failed             failed_digests 비우기
  --smoke-only               배포 없이 살아 있는 색을 대상으로 스모크만. 되돌리지 않고 보고만 한다
  --notify-failure <unit>    OnFailure= 전용. 알림 1건 보내고 종료
  --self-test                도커·네트워크·ntfy 없이 두 색 절차의 계약을 시험한다 (CI 가 호출)
환경변수: WONI_AGENT_CONF (기본 $HOME/woni/deploy/agent.conf)
EOF
}

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "필수 명령을 찾을 수 없습니다: $1" >&2
    return 1
  fi
}

# 설정은 3키뿐이고 하나라도 비면 즉시 죽는다 — 조용히 기본값으로 도는 것보다 OnFailure= 로 울리는 편이 안전하다.
# 파일 값은 로그·알림에 절대 출력하지 않는다(플랜 §4 규칙 7).
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
    echo "WONI_DEPLOY_DIR·WONI_NTFY_TOPIC·WONI_SMOKE_HOST 가 모두 있어야 합니다: $conf" >&2
    return 1
  fi
  OVERRIDE_FILE="$DEPLOY_DIR/docker-compose.override.yml"
}

# 배포 중 리부팅·OOM 이어도 커널이 락을 놓는다. mkdir 락은 stale lock 이 남아 이후 모든 주기가
# "스킵 → exit 0" 이 되고 OnFailure= 도 ntfy 도 안 울려 무기한 조용히 정지한다(플랜 §4 규칙 6).
with_lock() {
  require_command flock || return 1
  if ! mkdir -p "$STATE_DIR"; then
    echo "상태 디렉터리를 만들 수 없습니다: $STATE_DIR" >&2
    return 1
  fi
  exec 9>"$STATE_DIR/agent.lock"
  if ! flock -n 9; then
    log "다른 실행이 진행 중이라 이번 주기를 건너뜁니다."
    return 0
  fi
  "$@"
}

notify() {
  local message="$1" priority=default
  if [[ "$DRY_RUN" == true ]]; then
    log "[dry-run] 알림 생략"
    return 0
  fi
  case "$message" in
    🚨* | 🔴*) priority=urgent ;;
    *) ;;
  esac
  # 토픽이 curl 의 에러 문구로 새지 않도록 출력을 통째로 버리고 우리 문구만 남긴다(규칙 7).
  # 종료코드는 전파한다 — notify_hard 가 발송에 성공했을 때만 억제 타임스탬프를 남기기 위해서다.
  curl -sS -m 10 -H "Priority: $priority" -d "$message" "https://ntfy.sh/$NTFY_TOPIC" >/dev/null 2>&1 && return 0
  warn "ntfy 전송에 실패했습니다."
  return 1
}

state_init() {
  [[ "$DRY_RUN" == true ]] && return 0
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

# 상태 기록 실패로 배포 도중에 죽지 않는다 — 경고만 남긴다(상태 디렉터리 자체의 문제는 state_init 이 앞에서 잡는다).
state_set() {
  [[ "$DRY_RUN" == true ]] && return 0
  local filter="$1" temp
  shift
  new_temp temp "$STATE_DIR" || {
    warn "상태 파일 갱신에 실패했습니다."
    return 0
  }
  if jq "$@" "$filter" "$STATE_FILE" >"$temp" && mv -f "$temp" "$STATE_FILE"; then
    return 0
  fi
  warn "상태 파일 갱신에 실패했습니다."
}

is_failed_digest() {
  [[ -n "$1" ]] || return 1
  jq -e --arg d "$1" '(.failed_digests // []) | index($d) != null' "$STATE_FILE" >/dev/null 2>&1
}

mark_failed() {
  state_set '.failed_digests = ((.failed_digests // []) + [$d] | unique) | .last_failed_ref = ""' --arg d "$1"
}

short() { printf '%s' "${1:7:12}"; }

compose() { (cd "$DEPLOY_DIR" && docker compose "$@"); }

# 플랜 §4 「digest 조회」 — provenance attestation 때문에 단일 플랫폼 빌드여도 OCI index 다.
# $1=digest 를 담을 변수명. 0=성공 / 1=404(미승격, 조용히) / 2=조회 실패.
# 결과를 표준출력으로 돌려주면 호출부가 명령치환 서브셸이 되어 헤더 임시파일이 EXIT 정리에서 샌다.
probe_remote_digest() {
  local __token __headers __status __digest
  __token=$(curl -sS -m 15 "https://ghcr.io/token?scope=repository:${IMAGE_PATH}:pull&service=ghcr.io" 2>/dev/null |
    jq -r '.token // empty') || return 2
  [[ -n "$__token" ]] || return 2
  new_temp __headers || return 2
  __status=$(curl -sS -m 15 -I -o /dev/null -D "$__headers" -w '%{http_code}' \
    -H "Authorization: Bearer $__token" \
    -H 'Accept: application/vnd.oci.image.index.v1+json' \
    -H 'Accept: application/vnd.oci.image.manifest.v1+json' \
    -H 'Accept: application/vnd.docker.distribution.manifest.list.v2+json' \
    -H 'Accept: application/vnd.docker.distribution.manifest.v2+json' \
    "https://ghcr.io/v2/${IMAGE_PATH}/manifests/${DEPLOY_TAG}" 2>/dev/null) || return 2
  case "$__status" in
    200) ;;
    404) return 1 ;;
    *) return 2 ;;
  esac
  __digest=$(tr -d '\r' <"$__headers" | awk 'tolower($1) == "docker-content-digest:" { print $2 }' | tail -n 1)
  [[ "$__digest" =~ ^sha256:[0-9a-f]{64}$ ]] || return 2
  printf -v "$1" '%s' "$__digest"
}

handle_probe_failure() {
  local count
  count=$(state_get '.consecutive_probe_failures' 0)
  [[ "$count" =~ ^[0-9]+$ ]] || count=0
  count=$((count + 1))
  state_set '.consecutive_probe_failures = $v' --argjson v "$count"
  log "GHCR 조회에 실패했습니다(${count}회 연속). 이번 주기를 건너뜁니다."
  if ((count == PROBE_FAILURE_THRESHOLD)); then
    notify "⚠️ GHCR 조회가 ${count}회 연속 실패했습니다." || true
  fi
}

handle_probe_recovery() {
  local count
  count=$(state_get '.consecutive_probe_failures' 0)
  [[ "$count" =~ ^[0-9]+$ ]] || count=0
  ((count > 0)) || return 0
  state_set '.consecutive_probe_failures = 0'
  if ((count >= PROBE_FAILURE_THRESHOLD)); then
    notify "✅ GHCR 조회가 복구됐습니다." || true
  fi
}

# $1=서비스. 없으면 빈 문자열 = base 의 ${WONI_IMAGE_TAG:-latest} 로 열화한 상태(S20).
override_ref() {
  [[ -f "$OVERRIDE_FILE" ]] || return 0
  awk -v service="  $1:" '
    $0 == service { found = 1; next }
    found && $1 == "image:" { print $2; exit }
    /^  [^ ]/ { found = 0 }
  ' "$OVERRIDE_FILE"
}

# $1=컨테이너. RepoDigests 2단 inspect 는 값이 비면 템플릿 에러, 여럿이면 오답이라 쓰지 않는다.
container_image_ref() { docker inspect "$1" --format '{{.Config.Image}}' 2>/dev/null || true; }

# HEALTHCHECK 없는 이미지는 .State.Health 자체가 없어 방어하지 않으면 템플릿이 exit 1 로 죽는다.
container_health() {
  docker inspect "$1" --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' 2>/dev/null ||
    printf 'absent\n'
}

container_restart_count() { docker inspect "$1" --format '{{.RestartCount}}' 2>/dev/null || printf '0\n'; }

container_revision() {
  local rev
  rev=$(docker inspect "$1" --format '{{index .Config.Labels "org.opencontainers.image.revision"}}' 2>/dev/null) || return 0
  [[ "$rev" =~ ^[0-9a-f]{40}$ ]] && printf '%s\n' "$rev"
  return 0
}

# 게이트는 컨테이너 안 환경변수(이미지의 ENV WONI_DEPLOY_GATE_FILE)로만 다룬다 — 경로를 여기에 한 벌 더 두면
# 이미지와 어긋날 수 있다. 스크립트가 작은따옴표라 호스트 셸이 아니라 컨테이너 안 sh 가 변수를 확장한다. $1=컨테이너.
gate_open() { docker exec "$1" sh -c 'touch "$WONI_DEPLOY_GATE_FILE"'; }
gate_close() { docker exec "$1" sh -c 'rm -f "$WONI_DEPLOY_GATE_FILE"'; }
# 표준출력 open|closed. docker exec 자체의 실패(컨테이너 정지·데몬 오류)도 종료코드 1 이라 test -f 의 거짓과 구분되지
# 않는다(실측) — 판정을 컨테이너 안에서 글자로 찍고, 아무것도 못 받으면 호출부가 판정 불가로 읽는다.
gate_state() {
  docker exec "$1" sh -c 'if test -f "$WONI_DEPLOY_GATE_FILE"; then echo open; else echo closed; fi' 2>/dev/null
}
# 컷오버 이전 이미지는 이 변수가 없다 — readiness 경로도 없어 Caddy 가 영원히 비건강으로 본다(DESIGN §4 6단계).
gate_supported() { docker exec "$1" sh -c 'test -n "$WONI_DEPLOY_GATE_FILE"' >/dev/null 2>&1; }
# 게이트를 연 뒤 앱의 readiness 가 200 인지 컨테이너 안에서 본다. Caddy 가 실제로 회전에 넣었는지는 admin API 없이
# 확인할 수 없다 — 그 한계는 받아들이고 레버를 늘리지 않는다(ADR-016 신뢰 경계). $1=컨테이너.
gate_ready() {
  local status
  status=$(docker exec "$1" curl -sS -m 10 -o /dev/null -w '%{http_code}' \
    http://localhost:9091/actuator/health/readiness 2>/dev/null) || true
  [[ "$status" == 200 ]]
}

# DESIGN §4 「살아 있는 색 판정」의 입력. $1=색 → stopped|open|closed|unknown(상태나 게이트를 읽지 못함).
color_state() {
  local container="woni-api-$1" running gate
  # inspect 의 일시 실패(데몬 오류)를 멈춤으로 읽으면 유일하게 열린 색을 부트스트랩으로 오판해 up·게이트 초기화로
  # 서비스를 끊는다 — 컨테이너가 없다는 답일 때만 멈춤이다.
  if ! running=$(docker inspect "$container" --format '{{.State.Running}}' 2>&1); then
    case "$running" in
      *[Nn]o\ [Ss]uch\ [Oo]bject*) printf 'stopped\n' ;; # 운영 Docker 29 는 소문자, 맥 28 은 대문자로 답한다
      *) printf 'unknown\n' ;;
    esac
    return 0
  fi
  if [[ "$running" != true ]]; then
    printf 'stopped\n'
    return 0
  fi
  gate=$(gate_state "$container") || gate=""
  case "$gate" in
    open | closed) printf '%s\n' "$gate" ;;
    *) printf 'unknown\n' ;;
  esac
}

# EC2 Ubuntu 기본 TZ 는 UTC 다. 명시하지 않으면 창이 9시간 어긋나고, 틀려도 조용하며, 정작 11:05 를 밟는다.
is_hold_window() {
  local hhmm
  hhmm=$(TZ=Asia/Seoul date +%H%M)
  ((10#$hhmm >= HOLD_FROM && 10#$hhmm <= HOLD_TO))
}

notify_hold_once() {
  local digest="$1"
  [[ "$(state_get '.hold_notified_ref')" == "$digest" ]] && return 0
  # notify_hard 와 같은 순서다 — 먼저 기록하면 ntfy 순단 1회에 그 참조의 ⏸ 가 영구 유실된다.
  notify "⏸ 보류 창이라 배포를 미룹니다 $(short "$digest")" || return 0
  state_set '.hold_notified_ref = $d' --arg d "$digest"
}

# 미해소 상태는 종류별로 울리지 않고 일 1회 요약 1건으로 합친다(스크립트 상단 주석 참조).
daily_reminder() {
  local digest="$1" items="" unresolved now last
  if is_failed_digest "$digest"; then
    items="낙인된 digest 가 :deploy 에 남아 있습니다($(short "$digest"))"
  fi
  unresolved=$(state_get '.groupB_unresolved')
  if [[ -n "$unresolved" ]]; then
    items="${items:+$items · }미해소 스모크: $unresolved"
  fi
  [[ -n "$items" ]] || return 0
  now=$(date +%s)
  last=$(state_get '.stale_reminder_at' 0)
  [[ "$last" =~ ^[0-9]+$ ]] || last=0
  ((now - last >= REMINDER_INTERVAL_SEC)) || return 0
  notify "🔔 $items" || return 0
  state_set '.stale_reminder_at = $v' --argjson v "$now"
}

# 하드 실패는 조건이 남아 있는 한 매 주기(300초) 같은 알림을 다시 낸다 — 디스크 부족처럼 사람이 개입할
# 때까지 몇 시간 가는 상태면 하루 288건이 된다. 같은 사유는 24시간에 1건으로 접고, 사유가 바뀌면 즉시
# 울린다. 억제 상태는 성공 배포가 지우므로(deploy 말미) 영구 침묵이 되지 않는다.
# $2=digest. 비어 있지 않으면 사유키에 붙는다 — digest 에 종속된 사유(pull·기동·스모크 실패)를 문자열로만
# 접으면 실패한 이미지를 고쳐 새로 승격한 D2 의 첫 실패가 삼켜져 운영자가 그 운명을 전혀 모른다(S14).
# 반복 루프의 target digest 는 불변이므로 억제의 원래 목적은 그대로 달성된다. 디스크처럼 digest 와 무관한
# 사유는 비워서 넘긴다.
notify_hard() {
  local reason="$1" digest="$2" message="$3" key now last
  key="$reason${digest:+@$(short "$digest")}"
  now=$(date +%s)
  last=$(state_get '.hard_alert.at' 0)
  [[ "$last" =~ ^[0-9]+$ ]] || last=0
  if [[ "$(state_get '.hard_alert.reason')" == "$key" ]] && ((now - last < REMINDER_INTERVAL_SEC)); then
    log "같은 하드 실패가 24시간 안에 반복돼 알림을 접습니다."
    return 0
  fi
  # 전송 실패인데 타임스탬프를 남기면 ntfy 순단 1회가 그 사유를 24시간 침묵시킨다 — 성공했을 때만 접는다.
  notify "$message" || return 0
  state_set '.hard_alert = {reason: $r, at: $v}' --arg r "$key" --argjson v "$now"
}

preflight_disk() {
  local avail_mb
  # DEPLOY_DIR 기준이다 — 대상 호스트는 / 단일 볼륨이라 /var/lib/docker 와 같다(A1 이관 시 재확인).
  # df 를 못 읽으면 의도적으로 통과시킨다(fail-open) — 프리플라이트는 예방 장치이지 방어선이 아니고,
  # DEPLOY_DIR 자체가 없으면 곧바로 compose 가 죽어 그 경로로 알림이 나간다.
  avail_mb=$(df -Pm "$DEPLOY_DIR" | awk 'NR == 2 { print $4 }') || return 0
  [[ "$avail_mb" =~ ^[0-9]+$ ]] || return 0
  if ((avail_mb < DISK_MIN_MB)); then
    # 여유 용량은 호스트 안(로그)에만 남긴다 — 공개 토픽에는 고정 라벨만 싣는다(규칙 7).
    log "디스크 여유가 부족합니다(${avail_mb}MB < ${DISK_MIN_MB}MB)."
    # 디스크는 어떤 digest 를 배포하든 같은 상태라 사유키에 digest 를 붙이지 않는다(붙이면 승격마다 다시 운다).
    notify_hard disk "" "🚨 디스크 여유가 부족해 배포를 중단합니다."
    return 1
  fi
}

# sed -i 로 고치면 중간 상태를 compose 가 읽는다. mktemp 은 0600 이라 install 로 모드를 맞춘 사본을
# 같은 디렉터리에 만들고 mv 로 원자 교체한다(install 로 대상을 직접 덮으면 truncate 순간이 관측된다).
# 인자는 「서비스 참조」 쌍이다. 참조가 빈 쌍은 적지 않는다 — 그 서비스는 base 의 기본값으로 열화한 상태(S20)다.
write_override() {
  local temp staged
  # staged 도 new_temp 로 만든다 — mv 전에 죽어도 EXIT 정리가 지운다(직접 이름을 지으면 남는다).
  new_temp temp "$DEPLOY_DIR" || return 1
  new_temp staged "$DEPLOY_DIR" || return 1
  {
    echo "# tools/deploy-agent.sh 가 소유한다. 사람이 편집하지 않는다."
    echo "services:"
    while (($# >= 2)); do
      if [[ -n "$2" ]]; then
        printf '  %s:\n    image: %s\n' "$1" "$2"
      fi
      shift 2
    done
  } >"$temp" || return 1
  if install -m 644 "$temp" "$staged" && mv -f "$staged" "$OVERRIDE_FILE"; then
    rm -f "$temp"
    return 0
  fi
  rm -f "$staged"
  return 1
}

# 인자는 write_override 와 같은 두 쌍이다. 두 참조가 모두 비면 override 자체가 없던 상태다.
restore_override() {
  if [[ -n "$2" || -n "$4" ]]; then
    write_override "$@"
  else
    rm -f "$OVERRIDE_FILE"
  fi
}

# $1=컨테이너
wait_healthy() {
  local container="$1" waited=0
  while ((waited < HEALTH_TIMEOUT_SEC)); do
    if [[ "$(container_health "$container")" == healthy ]]; then
      return 0
    fi
    sleep "$HEALTH_POLL_SEC"
    waited=$((waited + HEALTH_POLL_SEC))
  done
  [[ "$(container_health "$container")" == healthy ]]
}

# 투입 전 직접 스모크(DESIGN §4 7단계) — 엣지를 거치지 않으므로 실패는 이미지 유래다. $1=컨테이너.
# 게이트만 닫힌 정상 상태라 9091 루트는 OUT_OF_SERVICE 여야 한다(DB 장애면 DOWN — DESIGN §2 표). 0=통과 / 1=불일치.
smoke_direct() {
  local container="$1" status rc=0
  status=$(docker exec "$container" curl -sS -m 10 -o /dev/null -w '%{http_code}' \
    http://localhost:8080/api/v1/assets 2>/dev/null) || true
  if [[ "$status" != 200 ]]; then
    warn "직접 스모크 /api/v1/assets=${status:-응답없음}"
    rc=1
  fi
  status=$(docker exec "$container" curl -sS -m 10 -o /dev/null -w '%{http_code}' \
    http://localhost:8080/api/v1/ledgers 2>/dev/null) || true
  if [[ "$status" != 401 ]]; then
    warn "직접 스모크 /api/v1/ledgers=${status:-응답없음}"
    rc=1
  fi
  if ! docker exec "$container" curl -sS -m 10 http://localhost:9091/actuator/health 2>/dev/null |
    grep -q '"status":"OUT_OF_SERVICE"'; then
    warn "직접 스모크 내부 actuator health 가 OUT_OF_SERVICE 가 아닙니다"
    rc=1
  fi
  return "$rc"
}

# 스모크는 로컬 caddy 를 직접 친다 — 인증서·SNI·Caddyfile 을 그대로 검증하면서 외부 왕복 의존을 없앤다.
edge_curl() {
  local out="$1"
  shift
  curl -sS -m 20 -o "$out" -w '%{http_code}' --resolve "$SMOKE_HOST:443:127.0.0.1" "$@"
}

# $1=투입된 색의 컨테이너. 0=통과 / 1=값 불일치(이미지 유래) / 2=엣지 연결 실패 / 3=로컬 준비 실패.
# 2·3 은 호스트 유래라 되돌리지도 낙인하지도 않는다 — ②③ 은 로컬 caddy 를 경유하므로 caddy 다운·TLS 만료(S22)를
# 이미지 탓으로 돌리면 정상 이미지가 --clear-failed 전까지 영구 스킵된다(D5, 그룹 B 와 같은 결).
# ① 은 docker exec 라 엣지를 거치지 않으므로 실패하면 이미지 유래다.
smoke_group_a() {
  local container="$1" out status mismatch=false conn_fail=false
  new_temp out || return 3
  if ! docker exec "$container" curl -fsS http://localhost:9091/actuator/health 2>/dev/null |
    grep -q '"status":"UP"'; then
    warn "스모크 A① 내부 actuator health 실패"
    mismatch=true
  fi
  if ! status=$(edge_curl "$out" "https://$SMOKE_HOST/api/v1/assets"); then
    warn "스모크 A② 엣지 연결 실패"
    conn_fail=true
  elif [[ "$status" != 200 ]]; then
    warn "스모크 A② /api/v1/assets=$status"
    mismatch=true
  fi
  if ! status=$(edge_curl "$out" "https://$SMOKE_HOST/api/v1/ledgers"); then
    warn "스모크 A③ 엣지 연결 실패"
    conn_fail=true
  elif [[ "$status" != 401 ]]; then
    warn "스모크 A③ /api/v1/ledgers=$status"
    mismatch=true
  fi
  [[ "$mismatch" == true ]] && return 1
  [[ "$conn_fail" == true ]] && return 2
  return 0
}

add_label() {
  case " $GROUPB_LABELS " in
    *" $1 "*) ;;
    *) GROUPB_LABELS+="$1 " ;;
  esac
}

# 무인증 요청은 헤더가 앱에 닿든 말든 401 이라 헤더 제거 회귀는 외부에서 관측할 수 없고, 본문 상한도 앱이
# 먼저 끊어 Caddy 계층을 응답으로 구분할 수 없다(smoke_group_b 주석). 그래서 두 축 모두 마운트된 Caddyfile 의
# 지시어 존재로 본다(플랜 §4). docker exec 라 엣지를 거치지 않으므로 엣지가 죽어 있어도 이 검사만은 그대로
# 유효하다. 0=통과 / 1=지시어 누락 / 2=caddy 컨테이너 접근 불가.
smoke_caddyfile() {
  local caddyfile directive rc=0
  new_temp caddyfile || return 2
  if ! docker exec "$CADDY_CONTAINER" cat /etc/caddy/Caddyfile >"$caddyfile" 2>/dev/null; then
    add_label "caddy접근"
    return 2
  fi
  for directive in '-Forwarded' '-X-Forwarded-Port' '-X-Real-IP'; do
    if ! grep -q -- "request_header $directive" "$caddyfile"; then
      rc=1
      add_label "헤더지시어"
    fi
  done
  # 본문 상한(max_size)과 그 413 봉투(handle_errors 413). 두 이름은 Caddyfile 주석에도 나오므로 줄머리
  # 앵커로 주석을 걸러낸다 — 들여쓰기는 탭이라 폭을 가정하지 않고 [[:space:]]* 로만 받는다.
  for directive in 'max_size' 'handle_errors 413'; do
    if ! grep -qE "^[[:space:]]*$directive" "$caddyfile"; then
      rc=1
      add_label "본문지시어"
    fi
  done
  return "$rc"
}

# 0=통과 / 1=값 불일치(소프트 경고) / 2=연결 자체 실패(🚨). 어느 쪽도 롤백하지 않는다(호스트 유래, D5).
smoke_group_b() {
  local out body post_out status rc=0 post_rc=0 caddy_rc=0
  GROUPB_LABELS=""
  new_temp out || return 2

  if ! status=$(edge_curl "$out" "https://$SMOKE_HOST/actuator/health"); then
    rc=2
    add_label "엣지연결"
  elif [[ "$status" != 404 ]]; then
    rc=$((rc > 1 ? rc : 1))
    add_label "actuator차단"
  fi

  new_temp body || return 2
  # 이 요청만 자기 응답 파일을 쓴다 — curl 은 연결 실패 시 -o 대상을 truncate 하지 않아, 공용 $out 을
  # 재사용하면 아래 봉투 grep 이 직전 요청의 잔여 본문을 보게 된다.
  new_temp post_out || return 2
  head -c "$SMOKE_BODY_BYTES" /dev/zero | tr '\0' 'a' >"$body"
  # 증명하는 것은 「상한이 엣지를 통해 end-to-end 로 돈다」까지다(플랜 §5 「413 + 한국어 봉투」). 어느 계층이
  # 끊었는지는 응답으로 구분할 수 없다 — 앱 상한(application.yml max-request-body-size 1536KB, 필터가 인증보다
  # 앞)이 Caddy 의 max_size 2MB 보다 낮고 Caddy 는 본문을 스트리밍으로 업스트림에 넘기므로, 앱이 먼저 413 을
  # 돌려주고 Caddy 는 그 응답을 그대로 전달한다. 즉 Caddy 봉투(REQUEST_TOO_LARGE)는 관측 자체가 불가능하고
  # 실제로 오는 것은 앱 봉투(REQUEST_BODY_TOO_LARGE)다(2026-08-06 운영 실측). 그래서 Caddy 계층
  # (request_body max_size·handle_errors 413) 의 회귀 탐지는 smoke_caddyfile 의 정적 검사가 담당한다.
  # 종료코드만으로는 판정하지 않는다 — 상한에 걸린 요청은 본문을 끝까지 받지 않고 413 이 돌아오므로 전송 도중
  # 스트림이 닫혀 curl 이 비-0(55/56/92)으로 끝날 수 있는데, 그 조기 종료는 지시어 유무와 무관하다.
  # 비-0 만으로 전송 실패로 몰면 「값 불일치 → 소프트」인 회귀가 「엣지 연결 전면 장애 → 🚨」로 오분류돼,
  # 같은 그룹의 다른 검사는 통과했는데 운영자만 caddy·TLS 를 뒤지게 된다. curl 은 상태줄을 못 받았을 때만
  # %{http_code} 에 000 을 쓰므로 그것을 함께 요구한다.
  status=$(edge_curl "$post_out" -X POST -H 'Content-Type: application/json' \
    --data-binary "@$body" "https://$SMOKE_HOST/api/v1/ledgers/import") || post_rc=$?
  # 봉투만 요구하면 ErrorCode 의 상태가 413 이 아니게 바뀌어도 본문 코드 문자열은 그대로라 초록으로 지나가고
  # iOS 가 보는 와이어 계약만 조용히 깨진다. 상태를 함께 요구해도 새 false-fail 은 없다 — 위와 같은 이유로
  # curl 은 상태줄을 못 받았을 때만 000 을 쓰므로 「봉투는 왔는데 상태는 못 받았다」는 성립하지 않는다.
  if ! { [[ "$status" == 413 ]] && grep -qE 'REQUEST_TOO_LARGE|REQUEST_BODY_TOO_LARGE' "$post_out"; }; then
    if ((post_rc != 0)) && [[ -z "$status" || "$status" == 000 ]]; then
      rc=2
      add_label "엣지연결"
    else
      rc=$((rc > 1 ? rc : 1))
      # 라벨은 진단 힌트다(rc 는 바꾸지 않는다). 413 인데 어느 봉투도 없으면 봉투가 통째로 사라진 회귀,
      # 봉투는 왔는데 413 이 아니면 상한 자체는 돌았고 상태코드만 회귀한 것, 둘 다 아니면 아무도 안 막은 것이다.
      if [[ "$status" == 413 ]]; then
        add_label "413봉투"
      elif grep -qE 'REQUEST_TOO_LARGE|REQUEST_BODY_TOO_LARGE' "$post_out"; then
        add_label "413상태"
      else
        add_label "413상한"
      fi
    fi
  fi

  if ! edge_curl "$out" "https://$SMOKE_HOST/" >/dev/null; then
    rc=2
    add_label "엣지연결"
  fi
  smoke_caddyfile || caddy_rc=$?
  rc=$((caddy_rc > rc ? caddy_rc : rc))
  return "$rc"
}

# 낙인하지 않는 실패는 다음 주기에 같은 R 을 그대로 재시도한다. 종료 조건이 없으면 기동↔중단이 5분마다 영원히
# 반복되며(알림은 접혀 조용하다), 그래서 플랜 §4 규칙 2 의 "같은 R 연속 2회 → 낙인"을 호출부가 아니라 여기서
# 판정한다 — brand=false 로 들어오는 모든 경로(기동 실패 포함)가 함께 끊긴다.
# $1=사유를 담은 변수명(낙인이면 꼬리를 붙인다), $2=digest, $3=낙인 여부.
record_failure() {
  local __digest="$2" __brand="$3"
  if [[ "$__brand" != true && "$(state_get '.last_failed_ref')" == "$__digest" ]]; then
    __brand=true
  fi
  if [[ "$__brand" == true ]]; then
    mark_failed "$__digest"
    # 낙인 여부를 사유에 담는다 — 1회차 ⚠️ 뒤 2회차에서 낙인으로 전환돼도 사유키가 같으면 "CD 가 멈췄고
    # --clear-failed 가 필요하다"는 상태 변화가 통째로 접힌다.
    printf -v "$1" '%s/낙인(해소 후 --clear-failed)' "${!1}"
  else
    state_set '.last_failed_ref = $d' --arg d "$__digest"
  fi
}

# 투입 전(DESIGN §4 3~8단계)의 실패 — T 는 아직 트래픽을 받지 않았으므로 L 은 건드리지 않고 T 만 멈춘다(사용자
# 영향 0). $6=true 면 🚨 — 실행 중 참조 불일치(플랜 §4 단언)만 쓴다. 서비스 중인 색이 없으면(부트스트랩) 늘 🚨 다.
abort_target() {
  local target="$1" live="$2" digest="$3" brand="$4" reason="$5" urgent="${6:-false}" prefix=⚠️ outcome
  record_failure reason "$digest" "$brand"
  compose stop "api-$target" || warn "api-$target 을 멈추지 못했습니다. 다음 주기가 닫힌 잔여로 보고 멈춥니다."
  if [[ -n "$live" ]]; then
    outcome="서비스는 $live 그대로"
  else
    outcome="서비스 중인 색이 없습니다"
    urgent=true
  fi
  [[ "$urgent" == true ]] && prefix=🚨
  notify_hard "$reason" "$digest" "$prefix $reason → 배포 중단 — $outcome $(short "$digest")"
}

# 투입 뒤(9·10단계)의 실패 — L 을 다시 열고 나서 T 를 빼고 멈춘다(콜드 스타트 없는 즉시 복귀, DESIGN §5 S3).
# 순서가 반대면 열린 색이 0 인 창이 생긴다. 게이트 조작이 중간에 실패하면 거기서 멈춘다 — 다음 주기의 판정표가
# 남은 상태(둘 다 열림 → 사람, 닫힌 T 잔여 → 정지)를 이어받는다.
revert_to_live() {
  local target="$1" live="$2" digest="$3" brand="$4" reason="$5"
  record_failure reason "$digest" "$brand"
  if [[ -z "$live" ]]; then
    # 부트스트랩이라 되돌릴 색이 없다 — T 를 빼면 서비스 중인 색이 0 이 되므로 그대로 두고 알린다.
    notify_hard "$reason/되돌릴색없음" "$digest" \
      "🚨 $reason — 되돌릴 색이 없어 $target 을 그대로 둡니다 $(short "$digest")"
    return 0
  fi
  log "되돌립니다: $live 를 다시 열고 $target 을 뺍니다."
  if gate_open "woni-api-$live" && sleep "$GATE_SETTLE_SEC" &&
    gate_close "woni-api-$target" && sleep "$GATE_SETTLE_SEC"; then
    compose stop "api-$target" || warn "api-$target 을 멈추지 못했습니다. 다음 주기가 닫힌 잔여로 보고 멈춥니다."
    notify_hard "$reason" "$digest" "⚠️ $reason → 배포 중단 — 서비스는 $live 그대로 $(short "$digest")"
  else
    notify_hard "$reason/되돌리기실패" "$digest" \
      "🚨 $reason → 되돌리기까지 실패했습니다. 두 색의 게이트를 확인하고 수동 개입 후 --clear-failed 를 실행하세요."
  fi
}

# DESIGN §4 전환 순서 1~12. $1=T(새 색) $2=L(살아 있는 색, 부트스트랩이면 빈 값) $3=R 참조 $4=R digest
# $5=L 의 실행 중 참조.
deploy() {
  local target="$1" live="$2" target_ref="$3" target_digest="$4" live_ref="$5"
  local target_container="woni-api-$1" other=blue previous_target_ref previous_other_ref
  local restart_baseline restarts running_digest group_a_rc=0 group_b_rc=0 brand=false soft="" a_label="" label rev
  SECONDS=0
  if [[ "$target" == blue ]]; then
    other=green
  fi

  preflight_disk || return 0
  previous_target_ref=$(override_ref "api-$target")
  previous_other_ref=$(override_ref "api-$other")
  # L 의 실행 중 참조를 함께 적는다 — 사람이 L 을 compose 로 다시 띄워도 지금 서비스 중인 이미지 그대로다.
  # L 이 없으면 기존 override 의 그 서비스 값을 유지하고, 그것도 없으면 적지 않는다.
  if ! write_override "api-$target" "$target_ref" "api-$other" "${live_ref:-$previous_other_ref}"; then
    notify_hard override "$target_digest" "🚨 이미지 참조 파일 교체에 실패했습니다 $(short "$target_digest")"
    return 0
  fi

  log "배포를 시작합니다: $(short "$target_digest") → $target (서비스 중 ${live:-없음})"
  # caddy 를 무인 업그레이드하면 Caddyfile 방어(2.11.x 실측 기반)가 조용히 깨지므로 T 만 pull 한다.
  if ! compose pull "api-$target"; then
    # 되돌려야 override 와 컨테이너가 다시 일치한다 — 배포는 없었는데 참조만 앞서간 상태로 두지 않는다.
    # 되돌리기까지 실패하면 사유를 나눠 알린다: "pull 실패" 만 나가면 운영자가 참조가 온전하다고
    # 오해하는데, 사유키가 다르므로 억제를 뚫고 즉시 울린다.
    if restore_override "api-$target" "$previous_target_ref" "api-$other" "$previous_other_ref"; then
      notify_hard pull "$target_digest" "🚨 이미지 pull 에 실패했습니다 $(short "$target_digest")"
    else
      notify_hard pull/되돌리기실패 "$target_digest" \
        "🚨 이미지 pull 실패 후 참조 되돌리기까지 실패했습니다 $(short "$target_digest")"
    fi
    return 0
  fi
  # 새 이미지면 재생성되어 게이트 파일이 없고, 같은 이미지의 멈춘 컨테이너면 그대로 시작한다.
  if ! compose up -d --no-deps "api-$target"; then
    abort_target "$target" "$live" "$target_digest" false "기동 실패"
    return 0
  fi
  # 사람이 rm 없이 열린 색을 compose stop 하면 게이트 파일이 쓰기 계층에 남고, 같은 이미지라 위 up 이 재생성 없이
  # 시작하면 스모크 전에 열린다. 기동(ApplicationReady)보다 먼저 한 번 닫아 둔다. 게이트 없는 이미지는 rm -f "" 라
  # 통과하고(jammy 실측 exit 0) 아래 게이트 지원 확인이 거절한다.
  if ! gate_close "$target_container"; then
    abort_target "$target" "$live" "$target_digest" false "게이트 초기화 실패"
    return 0
  fi

  restart_baseline=$(container_restart_count "$target_container")
  if ! wait_healthy "$target_container"; then
    restarts=$(container_restart_count "$target_container")
    # 크래시 루프는 RestartCount 가 늘고, 의존성 장애·기동 지연은 불변이다(플랜 §4 규칙 2).
    # 연속 2회 실패의 낙인 승격은 record_failure 가 공통으로 판정한다.
    if ((restarts > restart_baseline)); then
      brand=true
    fi
    abort_target "$target" "$live" "$target_digest" "$brand" "420초 내 healthy 미도달"
    return 0
  fi

  running_digest=$(container_image_ref "$target_container")
  running_digest="${running_digest##*@}"
  # 알림만 내고 끝내면 종료 조건이 없다 — 트리거가 L 의 실행 중 digest 라 단언이 실패하는 한 매 주기 트리거가
  # 다시 성립한다. abort_target 에 태워 ① 사유별 억제 ② 연속 2회 → 낙인으로 끊는다. up 이 이미 R 로
  # 재생성했는데도 참조가 다르면 재배포로 고칠 것이 없으니(B2 와 같은 논리) 반복을 멈추는 쪽이 맞다.
  # 이 경로만 긴급(6번째 인자)이다 — 플랜 §4 단언 「실행 중 참조 == R (아니면 성공 보고 금지 + 🚨)」.
  if [[ "$running_digest" != "$target_digest" ]]; then
    abort_target "$target" "$live" "$target_digest" false "실행 중 참조 불일치" true
    return 0
  fi
  # 게이트 없는 이미지(컷오버 이전 sha)는 투입하는 순간 서비스 불능이다 — 재배포로 고쳐지지 않으므로 1회로 낙인한다.
  if ! gate_supported "$target_container"; then
    abort_target "$target" "$live" "$target_digest" true "게이트 없는 이미지"
    return 0
  fi
  # 값 불일치만 1회로 낙인한다 — 응답값이 틀린 이미지는 재배포로 고쳐지지 않는다. DB 를 타는 검사라 healthy
  # 직후 Supabase 가 끊기면 정상 이미지도 낙인되지만, 그 창은 수초로 좁고 --clear-failed 로 해소된다.
  if ! smoke_direct "$target_container"; then
    abort_target "$target" "$live" "$target_digest" true "직접 스모크 응답 불일치"
    return 0
  fi

  # 투입 → 철수. 이 순서라 열린 색이 0 인 순간이 없고, L 은 진행 중 요청을 끝까지 처리한 뒤 회전에서 빠진다.
  if ! gate_open "$target_container"; then
    abort_target "$target" "$live" "$target_digest" false "게이트 투입 실패"
    return 0
  fi
  sleep "$GATE_SETTLE_SEC"
  # 투입이 확인되지 않으면 L 을 닫지 않는다 — 닫는 순간 열린 색이 0 이 된다. T 를 회전에서 먼저 빼고 기다린 뒤
  # 멈춘다(DESIGN §1 결정 3). 닫기가 실패해도 L 이 열려 있으므로 T 는 멈춘다.
  if ! gate_ready "$target_container"; then
    gate_close "$target_container" && sleep "$GATE_SETTLE_SEC"
    abort_target "$target" "$live" "$target_digest" false "투입 확인 실패"
    return 0
  fi
  if [[ -n "$live" ]]; then
    if ! gate_close "woni-api-$live"; then
      revert_to_live "$target" "$live" "$target_digest" false "게이트 철수 실패"
      return 0
    fi
    sleep "$GATE_SETTLE_SEC"
  fi

  # 엣지 스모크는 L 철수 뒤에 돈다 — 이제 Caddy 는 T 로만 보낸다.
  smoke_group_a "$target_container" || group_a_rc=$?
  case "$group_a_rc" in
    0) ;;
    1)
      revert_to_live "$target" "$live" "$target_digest" true "스모크 그룹 A 응답 불일치"
      return 0
      ;;
    2) a_label=그룹A엣지연결 ;;
    *) a_label=그룹A준비실패 ;;
  esac

  # 그룹 A 의 호스트 유래 실패는 A①(docker exec 내부 actuator)이 통과한 뒤에만 나온다 — 앱은 정상이고
  # 엣지·호스트만 안 되는 것이라 L 로 되돌려도 같은 caddy 를 거쳐 나아지는 것이 없다. 그래서
  # 되돌리지도 낙인하지도 않고 알림만 낸다(D5·S22). 배포를 그대로 두면 다음 주기가 R == L 로 스킵해
  # 전환↔되돌림이 5분마다 영원히 반복되지 않는다.
  if [[ -n "$a_label" ]]; then
    GROUPB_LABELS=""
    add_label "$a_label"
    # 엣지 3항목은 같은 이유로 실패해 왕복만 낭비하고 라벨도 겹친다. 엣지와 무관한 지시어 검사만 남긴다.
    smoke_caddyfile || true
    group_b_rc=2
  else
    smoke_group_b || group_b_rc=$?
  fi
  case "$group_b_rc" in
    0) state_set '.groupB_unresolved = ""' ;;
    1)
      soft=" ⚠️ 미해소: ${GROUPB_LABELS% }"
      state_set '.groupB_unresolved = $v' --arg v "${GROUPB_LABELS% }"
      ;;
    *) state_set '.groupB_unresolved = $v' --arg v "${GROUPB_LABELS% }" ;;
  esac

  # graceful 정지(compose stop_grace_period 를 따른다). 컨테이너는 지우지 않는다 — 직전 이미지를 붙들어 두고,
  # 다음 배포가 재생성한다. 실패해도 L 은 이미 닫혀 있어 다음 주기가 닫힌 잔여로 보고 멈춘다.
  if [[ -n "$live" ]] && ! compose stop "api-$live"; then
    warn "api-$live 를 멈추지 못했습니다. 다음 주기가 닫힌 잔여로 보고 멈춥니다."
  fi

  # 그룹 A·B 의 호스트 유래 실패는 이미지 승격을 막지 않는다(D5). 막으면 Caddyfile 미해소가 직전 성공 참조를
  # 영구 동결시킨다. 규칙 1 의 "스모크까지 통과했을 때만"이 겨냥하는 것은 이미지 유래 실패(그룹 A 값 불일치)이고
  # 그 경로는 위에서 되돌려 여기에 닿지 않는다.
  # hold_notified_ref 도 함께 비운다 — 같은 참조가 나중에 다시 보류되면 ⏸ 가 다시 울려야 한다.
  # hard_alert 도 비운다 — 배포가 끝까지 성공했으면 하드 실패 조건이 해소된 것이고, 다시 생기면 즉시 울려야 한다.
  state_set '.last_success_ref = $ref | .last_failed_ref = "" | .hold_notified_ref = "" | .hard_alert = null' \
    --arg ref "$target_ref"
  docker image prune -f >/dev/null 2>&1 || true

  label=$(short "$target_digest")
  rev=$(container_revision "$target_container")
  [[ -n "$rev" ]] && label="sha=${rev:0:7} $label"
  label="$target $label"
  if ((group_b_rc == 2)); then
    notify "🚨 배포는 됐으나 스모크가 실패했습니다 $label ${SECONDS}s${GROUPB_LABELS:+ (${GROUPB_LABELS% })}" || true
  else
    notify "✅ 배포 완료 $label ${SECONDS}s$soft" || true
  fi
}

# 판정표 2행 — 중단된 배포의 잔여(투입 전 T 이거나 철수했지만 못 멈춘 L). 닫혀 있으니 트래픽이 없다.
stop_leftover() {
  if [[ "$DRY_RUN" == true ]]; then
    log "[dry-run] 닫힌 채 떠 있는 $1 을 멈출 판정입니다."
    return 0
  fi
  log "닫힌 채 떠 있는 $1 을 멈춥니다(중단된 배포의 잔여)."
  compose stop "api-$1" || warn "api-$1 을 멈추지 못했습니다."
}

# 판정표 4행 — 열린 색이 없다(서비스 불능). 사람이 재생성해 게이트가 닫힌 경우(S6)가 대표다. 닫힌 색이 하나면
# 그 색을, 둘이면 실행 중 digest 가 last_success_ref 인 색을 직접 스모크한 뒤 연다. $1·$2=blue·green 의 상태.
readmit_closed() {
  local candidate="" success_digest color ref
  if [[ "$1" == closed && "$2" == closed ]]; then
    success_digest=$(state_get '.last_success_ref')
    success_digest="${success_digest##*@}"
    for color in blue green; do
      ref=$(container_image_ref "woni-api-$color")
      if [[ -z "$candidate" && -n "$success_digest" && "${ref##*@}" == "$success_digest" ]]; then
        candidate=$color
      fi
    done
  elif [[ "$1" == closed ]]; then
    candidate=blue
  else
    candidate=green
  fi
  if [[ -z "$candidate" ]]; then
    log "열린 색이 없고 닫힌 두 색 중 재투입할 색을 고를 수 없습니다."
    notify_hard live/선택불가 "" "🚨 열린 색이 없습니다 — 닫힌 두 색 중 재투입할 색을 고를 수 없어 수동 개입이 필요합니다."
    return 0
  fi
  # 사람이 막 재생성한 색(S6)은 기동이 끝난 뒤 다음 주기가 스모크한다. 기다리는 것은 starting 하나뿐이다 —
  # unhealthy·none 까지 기다리면 영구 비건강인 색이 알림 없는 장애가 된다.
  if [[ "$(container_health "woni-api-$candidate")" == starting ]]; then
    log "닫힌 $candidate 가 기동 중입니다. 다음 주기에 재투입을 판단합니다."
    return 0
  fi
  if [[ "$DRY_RUN" == true ]]; then
    log "판정: 서비스 불능 — $candidate 를 직접 스모크 뒤 재투입 (blue=$1 green=$2)"
    return 0
  fi
  if smoke_direct "woni-api-$candidate"; then
    if gate_open "woni-api-$candidate" && gate_ready "woni-api-$candidate"; then
      log "닫힌 $candidate 를 재투입했습니다."
      notify "🚨 닫힌 게이트를 재투입했습니다 — $candidate (열린 색이 없었습니다)" || true
      return 0
    fi
    # 파일만 남기면 다음 주기가 열린 색으로 읽어 정상(판정표 1행)으로 넘어가고 live/ 억제까지 풀려 무음 장애가 된다.
    gate_close "woni-api-$candidate" || warn "$candidate 의 게이트를 다시 닫지 못했습니다."
  fi
  notify_hard live/재투입실패 "" "🚨 열린 색이 없습니다 — $candidate 의 직접 스모크나 재투입이 실패해 수동 개입이 필요합니다."
}

run_cycle() {
  local blue green live="" target=blue row="" verdict remote_digest="" remote_ref live_ref="" probe_rc=0
  state_init || return 1

  # 살아 있는 색 판정(DESIGN §4 표) — 매 주기 처음, 공용 flock 안에서. 판정 불가가 끼지 않은 9가지 조합을 표의
  # 다섯 행으로 나눈다.
  blue=$(color_state blue)
  green=$(color_state green)
  case "$blue/$green" in
    unknown/* | */unknown)
      # 못 읽은 색을 멈춤·닫힘으로 읽으면 열린 색을 부트스트랩·잔여로 오판해 서비스 중인 색을 건드린다 — 판정하지 않는다.
      log "컨테이너 상태나 게이트를 읽지 못해 살아 있는 색을 판정할 수 없습니다(blue=$blue green=$green). 아무것도 하지 않습니다."
      notify_hard live/판정불가 "" "🚨 살아 있는 색을 판정할 수 없습니다 — 컨테이너 상태 확인(docker inspect·exec)이 실패했습니다."
      return 0
      ;;
    open/open)
      # 둘 다 서비스 가능하므로 장애가 아니고, 어느 쪽이 맞는지는 상태 밖에 있다 — 사람이 런북으로 정리한다(S8).
      log "두 색이 모두 열려 있습니다. 아무것도 하지 않습니다."
      notify_hard live/둘다열림 "" "🚨 두 색이 모두 열려 있습니다 — 런북대로 한쪽을 정리하세요."
      return 0
      ;;
    open/stopped) live=blue row=정상 ;;
    stopped/open) live=green row=정상 ;;
    open/closed)
      live=blue row=잔여정리
      stop_leftover green
      ;;
    closed/open)
      live=green row=잔여정리
      stop_leftover blue
      ;;
    stopped/stopped) row=부트스트랩 ;;
    *)
      readmit_closed "$blue" "$green"
      return 0
      ;;
  esac
  if [[ "$live" == blue ]]; then
    target=green
  fi
  verdict="살아 있는 색 ${live:-없음}, 판정표 $row, blue=$blue green=$green"
  # 열린 색이 정확히 하나로 돌아왔다(판정표 1·2행) — live/ 장애 알림의 억제를 푼다. 남겨 두면 수동 복구 뒤 같은 날
  # 재발한 서비스 불능이 같은 사유키로 24시간 접힌다.
  if [[ -n "$live" && "$(state_get '.hard_alert.reason')" == live/* ]]; then
    log "열린 색이 하나로 돌아와 live 장애 알림의 억제를 풉니다."
    state_set '.hard_alert = null'
  fi

  probe_remote_digest remote_digest || probe_rc=$?
  if ((probe_rc == 1)); then
    log ":deploy 태그가 아직 없습니다(미승격)."
    return 0
  fi
  if ((probe_rc != 0)); then
    handle_probe_failure
    return 0
  fi
  handle_probe_recovery

  remote_ref="$IMAGE_REPO@$remote_digest"
  # 전환 판정: R 이 L 의 실행 중 digest 와 같으면 배포하지 않는다. 태그로 뜬 L(S20 열화)은 @ 가 없어 전체 문자열이
  # 남으므로 다르다고 판정된다. L 이 없으면(부트스트랩) 언제나 배포 대상이다.
  if [[ -n "$live" ]]; then
    live_ref=$(container_image_ref "woni-api-$live")
    if [[ "$remote_digest" == "${live_ref##*@}" ]]; then
      log "변화가 없습니다($(short "$remote_digest")). 배포하지 않습니다($verdict)."
      # 철수(rm L) 뒤 성공 기록 전에 에이전트가 죽으면(정지 45초 창·재부팅) 이후 주기는 이 분기만 돌아 기록이 옛 참조로
      # 남고, 나중에 두 색이 닫히면 readmit_closed 가 옛 색을 고른다. 서비스 중인 참조로 맞춘다(알림 없음).
      # 낙인된 R 은 맞추지 않는다 — 부트스트랩 엣지 실패·되돌리기 실패 뒤 남은 R 이 성공으로 적히면 나중에 닫힌 두 색에서
      # 그쪽이 다시 열린다.
      if [[ "$(state_get '.last_success_ref')" != "$remote_ref" ]] && ! is_failed_digest "$remote_digest"; then
        log "last_success_ref 를 서비스 중인 참조($(short "$remote_digest"))로 맞춥니다."
        state_set '.last_success_ref = $ref' --arg ref "$remote_ref"
      fi
      daily_reminder "$remote_digest"
      return 0
    fi
  fi
  if is_failed_digest "$remote_digest"; then
    log "낙인된 digest 라 배포하지 않습니다($(short "$remote_digest"), $verdict)."
    daily_reminder "$remote_digest"
    return 0
  fi
  if is_hold_window; then
    log "보류 창(10:40~14:05 KST)이라 배포를 미룹니다($(short "$remote_digest"), $verdict)."
    notify_hold_once "$remote_digest"
    return 0
  fi

  if [[ "$DRY_RUN" == true ]]; then
    log "판정: 배포 대상 $(short "$remote_digest") → $target ($verdict)"
    return 0
  fi
  deploy "$target" "$live" "$remote_ref" "$remote_digest" "$live_ref"
}

clear_failed() {
  state_init || return 1
  # hard_alert 도 비운다 — 손으로 낙인을 푸는 것은 성공 배포와 같은 명시적 해소 신호다. 남겨 두면 개입
  # 직후 같은 digest 가 같은 사유로 재실패했을 때 사유키가 같아 24시간 접히고, 플래핑이 무음이 된다.
  state_set '.failed_digests = [] | .last_failed_ref = "" | .hard_alert = null'
  log "failed_digests 를 비웠습니다."
}

run_smoke_only() {
  local rc=0 group_a_rc=0 group_b_rc=0 blue green live=""
  state_init || return 1
  blue=$(color_state blue)
  green=$(color_state green)
  # A① 의 대상은 열린 색이 정확히 하나일 때만 정해진다. 아니면 대상이 비어 A① 이 실패로 남는다.
  case "$blue/$green" in
    open/open) ;;
    open/*) live=blue ;;
    */open) live=green ;;
    *) ;;
  esac
  log "스모크 대상: 살아 있는 색 ${live:-없음} (blue=$blue green=$green)"
  smoke_group_a "${live:+woni-api-$live}" || group_a_rc=$?
  case "$group_a_rc" in
    0) log "스모크 그룹 A: 통과" ;;
    1)
      log "스모크 그룹 A: 응답 불일치"
      rc=1
      ;;
    2)
      log "스모크 그룹 A: 엣지 연결 실패"
      rc=1
      ;;
    *)
      log "스모크 그룹 A: 로컬 준비 실패"
      rc=1
      ;;
  esac
  smoke_group_b || group_b_rc=$?
  case "$group_b_rc" in
    0)
      log "스모크 그룹 B: 통과"
      # 소프트 경고를 고치고 이 모드로 확인하면 다음 배포를 기다리지 않고 일 1회 🔔 이 멈춘다.
      state_set '.groupB_unresolved = ""'
      ;;
    1)
      log "스모크 그룹 B: 값 불일치(${GROUPB_LABELS% })"
      rc=1
      ;;
    *)
      log "스모크 그룹 B: 연결 실패(${GROUPB_LABELS% })"
      rc=1
      ;;
  esac
  log "보고 전용 모드라 되돌리지 않습니다."
  return "$rc"
}

# --- 자가 시험 --------------------------------------------------------------------------------------------------
# tools/monitoring-sync.sh 의 self_test 와 같은 방식이다 — docker·curl·flock·sleep·date·df 를 셸 함수로 덮어
# 도커·네트워크·ntfy 없이 두 색 절차를 실제 함수 그대로 돌리고, 모든 변경 행위를 사건 파일에 한 줄씩 남겨 순서까지
# 단언한다. 가짜 docker 의 컨테이너 상태는 케이스 밑 파일이다 — compose() 가 서브셸이라 변수에 두면 변경이 사라진다.

self_test_setup() {
  local case_dir="$SELF_TEST_ROOT/$1"
  STATE_DIR="$case_dir/state"
  STATE_FILE="$STATE_DIR/state.json"
  DEPLOY_DIR="$case_dir/deploy"
  OVERRIDE_FILE="$DEPLOY_DIR/docker-compose.override.yml"
  SELF_TEST_EVENTS="$case_dir/events"
  SELF_TEST_DOCKER="$case_dir/docker"
  # 케이스는 서브셸이라 부모의 EXIT 정리가 닿지 않는다 — 임시 파일을 케이스 밑에 만들어 마지막 rm -rf 로 회수한다.
  TMPDIR="$case_dir/tmp"
  TEMPS=()
  mkdir -p "$STATE_DIR" "$DEPLOY_DIR" "$SELF_TEST_DOCKER" "$TMPDIR"
  printf '{}\n' >"$STATE_FILE"
  : >"$SELF_TEST_EVENTS"
}

# $1=색 $2=running|stopped $3=open|closed $4=이미지 참조
self_test_color() {
  local dir="$SELF_TEST_DOCKER/woni-api-$1"
  mkdir -p "$dir"
  printf '%s\n' "$4" >"$dir/image"
  if [[ "$2" == running ]]; then
    : >"$dir/running"
  fi
  if [[ "$3" == open ]]; then
    : >"$dir/gate"
  fi
}

# $1=색 $2=컨테이너·이미지의 성질. 재생성(compose up)에도 남는다.
#   unhealthy           health 상태 + 9091 루트 DOWN(DB 장애, DESIGN §2 표)   starting   health 상태
#   crashloop           RestartCount 가 읽을 때마다 는다     digest_mismatch 실행 중 참조가 override 와 다르다
#   gateless            게이트 변수 없음      gate_ignored    게이트가 health 집계에 안 묶임(9091 루트 늘 UP)
#   smoke_bad           8080 assets=500      ledgers_open    8080 ledgers=200(인증 없이 열림)
#   not_ready           게이트를 열어도 readiness 503        pull_fail       compose pull 실패
#   exec_fail           docker exec 자체가 실패(출력 없이 1)  inspect_fail    docker inspect 가 데몬 오류로 실패
#   touch_fail·rm_fail  게이트 touch·rm 이 실패(사건은 남는다)
self_test_trait() {
  mkdir -p "$SELF_TEST_DOCKER/woni-api-$1"
  : >"$SELF_TEST_DOCKER/woni-api-$1/$2"
}

# 알림은 우선순위만 남긴다 — 문구까지 정확 비교하면 소요 시간(초) 같은 값에 흔들린다. 문구는 필요한 케이스가 따로 본다.
self_test_events() { sed -E 's/^(notify [a-z]+) .*/\1/' "$SELF_TEST_EVENTS"; }

# $@=기대 사건(순서대로). 사건 파일 전체가 정확히 이 목록이어야 한다 — 빠진 사건도 남는 사건도 실패다.
self_test_events_are() {
  local expected actual
  expected=$(printf '%s\n' "$@")
  actual=$(self_test_events)
  if [[ "$actual" != "$expected" ]]; then
    printf '  기대 사건:\n%s\n  실제 사건:\n%s\n' "$expected" "$actual" >&2
    return 1
  fi
}

self_test_docker() {
  case "$1" in
    compose)
      shift
      self_test_compose "$@"
      ;;
    inspect)
      shift
      self_test_inspect "$@"
      ;;
    exec)
      shift
      self_test_exec "$@"
      ;;
    *) printf 'docker %s\n' "$*" >>"$SELF_TEST_EVENTS" ;;
  esac
}

self_test_compose() {
  local service="${!#}" dir ref
  printf 'compose %s\n' "$*" >>"$SELF_TEST_EVENTS"
  # 서비스 하나를 지정하지 않거나 up 에 --no-deps 가 없으면 두 색과 caddy 를 함께 건드린다 — 거부한다.
  case "$*" in
    "pull $service" | "stop $service" | "up -d --no-deps $service") ;;
    *) return 1 ;;
  esac
  case "$service" in
    api-blue | api-green) ;;
    *) return 1 ;;
  esac
  dir="$SELF_TEST_DOCKER/woni-$service"
  case "$1" in
    pull) [[ ! -e "$dir/pull_fail" ]] ;;
    stop) rm -f "$dir/running" ;;
    up)
      mkdir -p "$dir"
      ref=$(override_ref "$service")
      # 이미지(설정)가 바뀌면 재생성이다 — 쓰기 계층의 게이트 파일과 RestartCount 가 사라진다.
      if [[ "$(cat "$dir/image" 2>/dev/null)" != "$ref" ]]; then
        rm -f "$dir/gate" "$dir/restarts"
        printf '%s\n' "$ref" >"$dir/image"
      fi
      : >"$dir/running"
      ;;
    *) ;;
  esac
}

self_test_inspect() {
  local dir="$SELF_TEST_DOCKER/$1" restarts=0
  if [[ ! -d "$dir" || "$2" != --format ]]; then
    printf '%s: %s\n' "${SELF_TEST_MISSING_WORDING:-error: no such object}" "$1" >&2
    return 1
  fi
  if [[ -e "$dir/inspect_fail" ]]; then
    printf 'Error response from daemon: self-test\n' >&2
    return 1
  fi
  case "$3" in
    '{{.State.Running}}')
      if [[ -e "$dir/running" ]]; then printf 'true\n'; else printf 'false\n'; fi
      ;;
    '{{.Config.Image}}')
      if [[ -e "$dir/digest_mismatch" ]]; then printf '%s\n' "$SELF_TEST_STALE"; else cat "$dir/image"; fi
      ;;
    *'.State.Health'*)
      if [[ -e "$dir/unhealthy" ]]; then
        printf 'unhealthy\n'
      elif [[ -e "$dir/starting" ]]; then
        printf 'starting\n'
      else
        printf 'healthy\n'
      fi
      ;;
    '{{.RestartCount}}')
      if [[ -f "$dir/restarts" ]]; then
        restarts=$(<"$dir/restarts")
      fi
      # 크래시 루프는 읽을 때마다 재시작이 하나씩 늘어 있다.
      if [[ -e "$dir/crashloop" ]]; then
        restarts=$((restarts + 1))
        printf '%s\n' "$restarts" >"$dir/restarts"
      fi
      printf '%s\n' "$restarts"
      ;;
    *'.Config.Labels'*) ;;
    *)
      printf 'inspect %s\n' "$3" >>"$SELF_TEST_EVENTS"
      return 1
      ;;
  esac
}

self_test_exec() {
  local container="$1" dir="$SELF_TEST_DOCKER/$1" color="${1#woni-api-}"
  shift
  if [[ "$container" == "$CADDY_CONTAINER" ]]; then
    if [[ "$*" == "cat /etc/caddy/Caddyfile" ]]; then
      printf '%s\n' 'request_header -Forwarded' 'request_header -X-Forwarded-Port' 'request_header -X-Real-IP' \
        $'\tmax_size 2MB' 'handle_errors 413 {'
      return 0
    fi
    printf 'caddy %s\n' "$*" >>"$SELF_TEST_EVENTS"
    return 1
  fi
  # 실제 docker exec 는 정지·데몬 오류 때 출력 없이 1 로 끝난다(실측) — 부수효과도 사건도 없다.
  if [[ ! -e "$dir/running" || -e "$dir/exec_fail" ]]; then
    return 1
  fi
  if [[ "$1" == curl ]]; then
    self_test_app_curl "$dir" "${!#}"
    return
  fi
  if [[ "$1 ${2:-}" != "sh -c" || $# -ne 3 ]]; then
    printf 'exec %s %s\n' "$color" "$*" >>"$SELF_TEST_EVENTS"
    return 1
  fi
  # 스크립트를 글자 그대로 비교한다 — 호스트 셸이 변수를 확장해 버리면(큰따옴표) 모르는 사건으로 남는다.
  case "$3" in
    'if test -f "$WONI_DEPLOY_GATE_FILE"; then echo open; else echo closed; fi')
      if [[ -e "$dir/gate" ]]; then printf 'open\n'; else printf 'closed\n'; fi
      ;;
    'test -n "$WONI_DEPLOY_GATE_FILE"') [[ ! -e "$dir/gateless" ]] ;;
    'touch "$WONI_DEPLOY_GATE_FILE"')
      printf 'touch %s\n' "$color" >>"$SELF_TEST_EVENTS"
      # 게이트 없는 이미지는 경로가 비어 touch 가 실패한다.
      [[ ! -e "$dir/gateless" && ! -e "$dir/touch_fail" ]] && : >"$dir/gate"
      ;;
    'rm -f "$WONI_DEPLOY_GATE_FILE"')
      printf 'rm %s\n' "$color" >>"$SELF_TEST_EVENTS"
      [[ ! -e "$dir/rm_fail" ]] && rm -f "$dir/gate"
      ;;
    *)
      printf 'exec %s %s\n' "$color" "$3" >>"$SELF_TEST_EVENTS"
      return 1
      ;;
  esac
}

# 컨테이너 안 curl(직접 스모크·A①). $1=컨테이너 디렉터리 $2=URL
self_test_app_curl() {
  case "$2" in
    http://localhost:9091/actuator/health)
      # 게이트 계약(DESIGN §2): 경로가 비면(게이트 없는 이미지) 늘 UP, 아니면 게이트 파일이 있을 때만 UP. DB 장애는 DOWN.
      if [[ -e "$1/unhealthy" ]]; then
        printf '{"status":"DOWN","groups":["liveness","readiness"]}'
      elif [[ -e "$1/gateless" || -e "$1/gate_ignored" || -e "$1/gate" ]]; then
        printf '{"status":"UP","groups":["liveness","readiness"]}'
      else
        printf '{"status":"OUT_OF_SERVICE","groups":["liveness","readiness"]}'
      fi
      ;;
    http://localhost:9091/actuator/health/readiness)
      if [[ ! -e "$1/not_ready" && (-e "$1/gate_ignored" || -e "$1/gate") ]]; then printf 200; else printf 503; fi
      ;;
    http://localhost:8080/api/v1/assets)
      if [[ -e "$1/smoke_bad" ]]; then printf 500; else printf 200; fi
      ;;
    http://localhost:8080/api/v1/ledgers)
      if [[ -e "$1/ledgers_open" ]]; then printf 200; else printf 401; fi
      ;;
    *)
      printf 'exec curl %s\n' "$2" >>"$SELF_TEST_EVENTS"
      return 1
      ;;
  esac
}

# 호스트 curl — GHCR 조회·ntfy·엣지 스모크만 흉내 낸다. 모르는 URL 은 사건으로 남기고 실패한다.
self_test_curl() {
  local out="" headers="" data="" priority=default url=""
  while (($# > 0)); do
    case "$1" in
      -o)
        out="$2"
        shift 2
        ;;
      -D)
        headers="$2"
        shift 2
        ;;
      -d)
        data="$2"
        shift 2
        ;;
      -H)
        if [[ "$2" == "Priority: "* ]]; then
          priority="${2#Priority: }"
        fi
        shift 2
        ;;
      -m | -w | -X | --resolve | --data-binary) shift 2 ;;
      -*) shift ;;
      *)
        url="$1"
        shift
        ;;
    esac
  done
  case "$url" in
    # 토픽(URL)은 남기지 않는다 — 시크릿을 사건 파일에 내지 않는다(규칙 7).
    https://ntfy.sh/*) printf 'notify %s %s\n' "$priority" "$data" >>"$SELF_TEST_EVENTS" ;;
    https://ghcr.io/token*) printf '{"token":"self-test"}\n' ;;
    https://ghcr.io/v2/*)
      printf 'Docker-Content-Digest: %s\r\n' "$SELF_TEST_REMOTE_DIGEST" >"$headers"
      printf 200
      ;;
    "https://$SMOKE_HOST/"*) self_test_edge "/${url#"https://$SMOKE_HOST/"}" "$out" ;;
    *)
      printf 'curl %s\n' "$url" >>"$SELF_TEST_EVENTS"
      return 1
      ;;
  esac
}

# 엣지(Caddy 경유) 스모크의 정상 응답. SELF_TEST_EDGE_ASSETS 로 A② 만 바꾸고, SELF_TEST_EDGE_DOWN=true 면 상태줄
# 없이 연결 실패(curl exit 7)로 끝난다.
self_test_edge() {
  printf 'edge %s\n' "$1" >>"$SELF_TEST_EVENTS"
  if [[ "${SELF_TEST_EDGE_DOWN:-false}" == true ]]; then
    return 7
  fi
  case "$1" in
    /api/v1/assets) printf '%s' "${SELF_TEST_EDGE_ASSETS:-200}" ;;
    /api/v1/ledgers) printf 401 ;;
    /actuator/health) printf 404 ;;
    /api/v1/ledgers/import)
      printf '{"code":"REQUEST_BODY_TOO_LARGE"}' >"$2"
      printf 413
      ;;
    *) printf 200 ;;
  esac
}

self_test_switch_order() {
  self_test_setup switch-order
  self_test_color blue running open "$SELF_TEST_OLD"
  self_test_color green stopped closed "$SELF_TEST_OLD"
  with_lock run_cycle >/dev/null 2>&1
  # 게이트 초기화(rm green) → 투입(touch green) → 철수(rm blue) → 엣지 스모크 → 정지(stop api-blue). caddy 를 향한
  # 사건은 하나도 없다.
  self_test_events_are \
    'compose pull api-green' 'compose up -d --no-deps api-green' 'rm green' \
    'touch green' "sleep $GATE_SETTLE_SEC" 'rm blue' "sleep $GATE_SETTLE_SEC" \
    'edge /api/v1/assets' 'edge /api/v1/ledgers' 'edge /actuator/health' 'edge /api/v1/ledgers/import' 'edge /' \
    'compose stop api-blue' 'docker image prune -f' 'notify default' &&
    ! grep -q caddy "$SELF_TEST_EVENTS" &&
    [[ "$(color_state green)/$(color_state blue)" == open/stopped ]] &&
    [[ "$(state_get '.last_success_ref')" == "$SELF_TEST_NEW" ]]
}

self_test_green_live_deploys_to_blue() {
  self_test_setup green-live
  self_test_color green running open "$SELF_TEST_OLD"
  self_test_color blue stopped closed "$SELF_TEST_OLD"
  with_lock run_cycle >/dev/null 2>&1
  self_test_events_are \
    'compose pull api-blue' 'compose up -d --no-deps api-blue' 'rm blue' \
    'touch blue' "sleep $GATE_SETTLE_SEC" 'rm green' "sleep $GATE_SETTLE_SEC" \
    'edge /api/v1/assets' 'edge /api/v1/ledgers' 'edge /actuator/health' 'edge /api/v1/ledgers/import' 'edge /' \
    'compose stop api-green' 'docker image prune -f' 'notify default' &&
    [[ "$(override_ref api-blue)" == "$SELF_TEST_NEW" && "$(override_ref api-green)" == "$SELF_TEST_OLD" ]] &&
    [[ "$(color_state blue)/$(color_state green)" == open/stopped ]]
}

self_test_override_keeps_live_ref() {
  self_test_setup override-keeps-live-ref
  self_test_color blue running open "$SELF_TEST_OLD"
  self_test_color green stopped closed "$SELF_TEST_OLD"
  # override 에 남은 값보다 L 의 실행 중 참조가 이긴다.
  write_override api-blue "$SELF_TEST_STALE" api-green "$SELF_TEST_STALE"
  with_lock run_cycle >/dev/null 2>&1
  [[ "$(override_ref api-green)" == "$SELF_TEST_NEW" && "$(override_ref api-blue)" == "$SELF_TEST_OLD" ]]
}

self_test_stale_gate_closed_after_up() {
  self_test_setup stale-gate
  self_test_color blue running open "$SELF_TEST_OLD"
  # 사람이 rm 없이 멈춘 green — 게이트 파일이 남았고 이미지가 R 과 같아 up 이 재생성하지 않는다.
  self_test_color green stopped open "$SELF_TEST_NEW"
  with_lock run_cycle >/dev/null 2>&1
  # 닫지 않으면 직접 스모크가 9091 루트 UP 을 받아 거절한다 — 정상 전환까지 가는 것이 곧 닫혔다는 증거다.
  self_test_events_are \
    'compose pull api-green' 'compose up -d --no-deps api-green' 'rm green' \
    'touch green' "sleep $GATE_SETTLE_SEC" 'rm blue' "sleep $GATE_SETTLE_SEC" \
    'edge /api/v1/assets' 'edge /api/v1/ledgers' 'edge /actuator/health' 'edge /api/v1/ledgers/import' 'edge /' \
    'compose stop api-blue' 'docker image prune -f' 'notify default' &&
    [[ "$(color_state green)/$(color_state blue)" == open/stopped ]]
}

self_test_gate_init_failure_aborts() {
  self_test_setup gate-init-failure
  self_test_color blue running open "$SELF_TEST_OLD"
  self_test_color green stopped closed "$SELF_TEST_OLD"
  self_test_trait green rm_fail
  with_lock run_cycle >/dev/null 2>&1
  # 일시 실패일 수 있어 1회로는 낙인하지 않는다(같은 R 연속 2회 규칙이 끊는다).
  self_test_events_are 'compose pull api-green' 'compose up -d --no-deps api-green' 'rm green' \
    'compose stop api-green' 'notify default' &&
    grep -q '게이트 초기화 실패' "$SELF_TEST_EVENTS" &&
    ! is_failed_digest "$SELF_TEST_REMOTE_DIGEST" &&
    [[ "$(state_get '.last_failed_ref')" == "$SELF_TEST_REMOTE_DIGEST" ]] &&
    [[ "$(color_state blue)" == open ]]
}

self_test_pull_failure_restores_override() {
  self_test_setup pull-failure
  self_test_color blue running open "$SELF_TEST_OLD"
  self_test_color green stopped closed "$SELF_TEST_OLD"
  self_test_trait green pull_fail
  # 배포는 두 값을 모두 바꾼다(api-green=R, api-blue=blue 의 실행 중 참조) — 복원이 두 서비스 모두에서 보인다.
  write_override api-blue "$SELF_TEST_STALE" api-green "$SELF_TEST_STALE"
  with_lock run_cycle >/dev/null 2>&1
  self_test_events_are 'compose pull api-green' 'notify urgent' &&
    [[ "$(override_ref api-blue)" == "$SELF_TEST_STALE" && "$(override_ref api-green)" == "$SELF_TEST_STALE" ]]
}

self_test_direct_smoke_failure_keeps_live() {
  self_test_setup direct-smoke-failure
  self_test_color blue running open "$SELF_TEST_OLD"
  self_test_color green stopped closed "$SELF_TEST_OLD"
  self_test_trait green smoke_bad
  with_lock run_cycle >/dev/null 2>&1
  self_test_events_are 'compose pull api-green' 'compose up -d --no-deps api-green' 'rm green' \
    'compose stop api-green' 'notify default' &&
    grep -q '배포 중단 — 서비스는 blue 그대로' "$SELF_TEST_EVENTS" &&
    ! grep -q '롤백' "$SELF_TEST_EVENTS" &&
    is_failed_digest "$SELF_TEST_REMOTE_DIGEST" &&
    [[ "$(color_state blue)" == open ]]
}

self_test_ineffective_gate_refused() {
  self_test_setup ineffective-gate
  self_test_color blue running open "$SELF_TEST_OLD"
  self_test_color green stopped closed "$SELF_TEST_OLD"
  # 게이트 변수는 있으나 readiness 집계에 묶이지 않은 이미지 — 닫혀 있어도 9091 루트가 UP 이다.
  self_test_trait green gate_ignored
  with_lock run_cycle >/dev/null 2>&1
  self_test_events_are 'compose pull api-green' 'compose up -d --no-deps api-green' 'rm green' \
    'compose stop api-green' 'notify default' &&
    grep -q '직접 스모크 응답 불일치' "$SELF_TEST_EVENTS" &&
    is_failed_digest "$SELF_TEST_REMOTE_DIGEST"
}

self_test_ledgers_not_401_refused() {
  self_test_setup ledgers-not-401
  self_test_color blue running open "$SELF_TEST_OLD"
  self_test_color green stopped closed "$SELF_TEST_OLD"
  self_test_trait green ledgers_open
  with_lock run_cycle >/dev/null 2>&1
  self_test_events_are 'compose pull api-green' 'compose up -d --no-deps api-green' 'rm green' \
    'compose stop api-green' 'notify default' &&
    grep -q '직접 스모크 응답 불일치' "$SELF_TEST_EVENTS" &&
    is_failed_digest "$SELF_TEST_REMOTE_DIGEST"
}

self_test_gate_open_failure_keeps_live() {
  self_test_setup gate-open-failure
  self_test_color blue running open "$SELF_TEST_OLD"
  self_test_color green stopped closed "$SELF_TEST_OLD"
  self_test_trait green touch_fail
  with_lock run_cycle >/dev/null 2>&1
  self_test_events_are 'compose pull api-green' 'compose up -d --no-deps api-green' 'rm green' 'touch green' \
    'compose stop api-green' 'notify default' &&
    grep -q '게이트 투입 실패' "$SELF_TEST_EVENTS" &&
    [[ "$(color_state blue)" == open ]]
}

self_test_target_not_ready_keeps_live() {
  self_test_setup target-not-ready
  self_test_color blue running open "$SELF_TEST_OLD"
  self_test_color green stopped closed "$SELF_TEST_OLD"
  self_test_trait green not_ready
  with_lock run_cycle >/dev/null 2>&1
  # 투입이 확인되지 않으면 blue 를 닫지 않는다 — green 을 빼고 기다린 뒤 멈춘다.
  self_test_events_are 'compose pull api-green' 'compose up -d --no-deps api-green' 'rm green' \
    'touch green' "sleep $GATE_SETTLE_SEC" 'rm green' "sleep $GATE_SETTLE_SEC" \
    'compose stop api-green' 'notify default' &&
    grep -q '투입 확인 실패' "$SELF_TEST_EVENTS" &&
    ! is_failed_digest "$SELF_TEST_REMOTE_DIGEST" &&
    [[ "$(color_state blue)/$(color_state green)" == open/stopped ]]
}

self_test_gate_close_failure_reverts() {
  self_test_setup gate-close-failure
  self_test_color blue running open "$SELF_TEST_OLD"
  self_test_color green stopped closed "$SELF_TEST_OLD"
  self_test_trait blue rm_fail
  with_lock run_cycle >/dev/null 2>&1
  # 철수 실패는 투입 뒤의 실패라 blue 를 다시 열고 green 을 빼서 멈춘다.
  self_test_events_are 'compose pull api-green' 'compose up -d --no-deps api-green' 'rm green' \
    'touch green' "sleep $GATE_SETTLE_SEC" 'rm blue' \
    'touch blue' "sleep $GATE_SETTLE_SEC" 'rm green' "sleep $GATE_SETTLE_SEC" \
    'compose stop api-green' 'notify default' &&
    grep -q '게이트 철수 실패' "$SELF_TEST_EVENTS" &&
    [[ "$(color_state blue)/$(color_state green)" == open/stopped ]]
}

self_test_edge_failure_restores_live() {
  self_test_setup edge-failure
  self_test_color blue running open "$SELF_TEST_OLD"
  self_test_color green stopped closed "$SELF_TEST_OLD"
  SELF_TEST_EDGE_ASSETS=500
  with_lock run_cycle >/dev/null 2>&1
  # 되돌림은 touch blue → rm green → stop api-green 순서이고, blue 는 멈추지 않는다.
  self_test_events_are \
    'compose pull api-green' 'compose up -d --no-deps api-green' 'rm green' \
    'touch green' "sleep $GATE_SETTLE_SEC" 'rm blue' "sleep $GATE_SETTLE_SEC" \
    'edge /api/v1/assets' 'edge /api/v1/ledgers' \
    'touch blue' "sleep $GATE_SETTLE_SEC" 'rm green' "sleep $GATE_SETTLE_SEC" \
    'compose stop api-green' 'notify default' &&
    is_failed_digest "$SELF_TEST_REMOTE_DIGEST" &&
    [[ "$(color_state blue)/$(color_state green)" == open/stopped ]]
}

self_test_revert_failure_alerts() {
  self_test_setup revert-failure
  self_test_color blue running open "$SELF_TEST_OLD"
  self_test_color green stopped closed "$SELF_TEST_OLD"
  self_test_trait blue touch_fail
  SELF_TEST_EDGE_ASSETS=500
  with_lock run_cycle >/dev/null 2>&1
  # 되돌리기가 첫 조작에서 막히면 거기서 멈춘다 — green 을 닫거나 멈추면 열린 색이 0 이 된다.
  self_test_events_are \
    'compose pull api-green' 'compose up -d --no-deps api-green' 'rm green' \
    'touch green' "sleep $GATE_SETTLE_SEC" 'rm blue' "sleep $GATE_SETTLE_SEC" \
    'edge /api/v1/assets' 'edge /api/v1/ledgers' 'touch blue' 'notify urgent' &&
    [[ "$(state_get '.hard_alert.reason')" == *되돌리기실패* ]] &&
    is_failed_digest "$SELF_TEST_REMOTE_DIGEST" &&
    [[ "$(color_state green)/$(color_state blue)" == open/closed ]]
}

self_test_edge_unreachable_keeps_target() {
  self_test_setup edge-unreachable
  self_test_color blue running open "$SELF_TEST_OLD"
  self_test_color green stopped closed "$SELF_TEST_OLD"
  SELF_TEST_EDGE_DOWN=true
  with_lock run_cycle >/dev/null 2>&1
  # 호스트 유래(D5) — 되돌리지도 낙인하지도 않고 배포를 끝낸 뒤 🚨 로 알린다.
  self_test_events_are \
    'compose pull api-green' 'compose up -d --no-deps api-green' 'rm green' \
    'touch green' "sleep $GATE_SETTLE_SEC" 'rm blue' "sleep $GATE_SETTLE_SEC" \
    'edge /api/v1/assets' 'edge /api/v1/ledgers' \
    'compose stop api-blue' 'docker image prune -f' 'notify urgent' &&
    grep -q '배포는 됐으나' "$SELF_TEST_EVENTS" &&
    ! is_failed_digest "$SELF_TEST_REMOTE_DIGEST" &&
    [[ "$(state_get '.last_success_ref')" == "$SELF_TEST_NEW" ]] &&
    [[ "$(color_state green)/$(color_state blue)" == open/stopped ]]
}

self_test_gateless_image_refused() {
  self_test_setup gateless-image
  self_test_color blue running open "$SELF_TEST_OLD"
  self_test_color green stopped closed "$SELF_TEST_OLD"
  self_test_trait green gateless
  with_lock run_cycle >/dev/null 2>&1
  # 게이트 초기화는 rm -f "" 라 통과한다. 게이트 없는 이미지는 루트가 UP 이라 직접 스모크도 불일치로 잡는다 —
  # 사유로 이 단계가 거절했음을 못박는다.
  self_test_events_are 'compose pull api-green' 'compose up -d --no-deps api-green' 'rm green' \
    'compose stop api-green' 'notify default' &&
    grep -q '게이트 없는 이미지' "$SELF_TEST_EVENTS" &&
    is_failed_digest "$SELF_TEST_REMOTE_DIGEST"
}

self_test_unhealthy_target_keeps_live() {
  local sleeps=() i
  self_test_setup unhealthy-target
  self_test_color blue running open "$SELF_TEST_OLD"
  self_test_color green stopped closed "$SELF_TEST_OLD"
  self_test_trait green unhealthy
  self_test_trait green crashloop
  for ((i = 0; i < HEALTH_TIMEOUT_SEC / HEALTH_POLL_SEC; i++)); do
    sleeps+=("sleep $HEALTH_POLL_SEC")
  done
  with_lock run_cycle >/dev/null 2>&1
  # 정확 비교라 blue 를 향한 사건(touch·rm·stop)이 하나도 없음까지 함께 못박힌다.
  self_test_events_are 'compose pull api-green' 'compose up -d --no-deps api-green' 'rm green' "${sleeps[@]}" \
    'compose stop api-green' 'notify default' &&
    is_failed_digest "$SELF_TEST_REMOTE_DIGEST"
}

self_test_running_digest_mismatch_aborts() {
  self_test_setup digest-mismatch
  self_test_color blue running open "$SELF_TEST_OLD"
  self_test_color green stopped closed "$SELF_TEST_OLD"
  self_test_trait green digest_mismatch
  with_lock run_cycle >/dev/null 2>&1
  self_test_events_are 'compose pull api-green' 'compose up -d --no-deps api-green' 'rm green' \
    'compose stop api-green' 'notify urgent' &&
    grep -q '실행 중 참조 불일치' "$SELF_TEST_EVENTS" &&
    [[ "$(color_state blue)" == open ]]
}

self_test_noop_when_live_runs_target() {
  self_test_setup noop
  self_test_color blue running open "$SELF_TEST_NEW"
  self_test_color green stopped closed "$SELF_TEST_OLD"
  with_lock run_cycle >/dev/null 2>&1
  self_test_events_are
}

self_test_noop_reconciles_last_success() {
  self_test_setup noop-reconcile
  self_test_color blue running open "$SELF_TEST_NEW"
  self_test_color green stopped closed "$SELF_TEST_OLD"
  # 철수 뒤 성공 기록 전에 죽은 주기의 흔적 — 서비스 중인 것은 R 인데 기록은 옛 참조다.
  state_set '.last_success_ref = $ref' --arg ref "$SELF_TEST_OLD"
  with_lock run_cycle >/dev/null 2>&1
  self_test_events_are &&
    [[ "$(state_get '.last_success_ref')" == "$SELF_TEST_NEW" ]]
}

self_test_noop_skips_branded_reconcile() {
  self_test_setup noop-branded
  self_test_color blue running open "$SELF_TEST_NEW"
  self_test_color green stopped closed "$SELF_TEST_OLD"
  # 부트스트랩 엣지 실패 뒤처럼 낙인된 R 이 서비스 중이다.
  state_set '.last_success_ref = $ref | .failed_digests = [$d]' \
    --arg ref "$SELF_TEST_OLD" --arg d "$SELF_TEST_REMOTE_DIGEST"
  with_lock run_cycle >/dev/null 2>&1
  # 낙인 리마인더(🔔) 1건 말고는 사건이 없고, 성공 기록은 옛 참조 그대로다.
  self_test_events_are 'notify default' &&
    grep -q '낙인된 digest' "$SELF_TEST_EVENTS" &&
    [[ "$(state_get '.last_success_ref')" == "$SELF_TEST_OLD" ]]
}

self_test_bootstrap_without_live() {
  self_test_setup bootstrap
  self_test_color blue stopped closed "$SELF_TEST_OLD"
  self_test_color green stopped closed "$SELF_TEST_OLD"
  with_lock run_cycle >/dev/null 2>&1
  # 철수 단계(rm·stop)가 없다(up 직후의 rm blue 는 투입 전 게이트 초기화다). L 도 기존 override 도 없으니 api-green 은
  # override 에 적지 않는다.
  self_test_events_are \
    'compose pull api-blue' 'compose up -d --no-deps api-blue' 'rm blue' 'touch blue' "sleep $GATE_SETTLE_SEC" \
    'edge /api/v1/assets' 'edge /api/v1/ledgers' 'edge /actuator/health' 'edge /api/v1/ledgers/import' 'edge /' \
    'docker image prune -f' 'notify default' &&
    [[ "$(override_ref api-blue)" == "$SELF_TEST_NEW" && -z "$(override_ref api-green)" ]] &&
    [[ "$(color_state blue)" == open ]]
}

self_test_bootstrap_edge_failure_keeps_target() {
  self_test_setup bootstrap-edge-failure
  self_test_color blue stopped closed "$SELF_TEST_OLD"
  self_test_color green stopped closed "$SELF_TEST_OLD"
  SELF_TEST_EDGE_ASSETS=500
  with_lock run_cycle >/dev/null 2>&1
  # 되돌릴 색이 없다 — 투입 뒤에는 blue 를 닫지도 멈추지도 않는다.
  self_test_events_are \
    'compose pull api-blue' 'compose up -d --no-deps api-blue' 'rm blue' 'touch blue' "sleep $GATE_SETTLE_SEC" \
    'edge /api/v1/assets' 'edge /api/v1/ledgers' 'notify urgent' &&
    grep -q '되돌릴 색이 없어' "$SELF_TEST_EVENTS" &&
    is_failed_digest "$SELF_TEST_REMOTE_DIGEST" &&
    [[ "$(color_state blue)" == open ]]
}

self_test_closed_single_color_is_readmitted() {
  self_test_setup readmit
  self_test_color blue running closed "$SELF_TEST_OLD"
  self_test_color green stopped closed "$SELF_TEST_OLD"
  # R 이 blue 와 달라도 이번 주기는 재투입만 한다.
  with_lock run_cycle >/dev/null 2>&1
  self_test_events_are 'touch blue' 'notify urgent' &&
    [[ "$(color_state blue)" == open ]]
}

self_test_readmit_smoke_failure_keeps_closed() {
  self_test_setup readmit-smoke-failure
  self_test_color blue running closed "$SELF_TEST_OLD"
  self_test_color green stopped closed "$SELF_TEST_OLD"
  self_test_trait blue smoke_bad
  with_lock run_cycle >/dev/null 2>&1
  self_test_events_are 'notify urgent' &&
    [[ "$(state_get '.hard_alert.reason')" == live/재투입실패 ]] &&
    [[ "$(color_state blue)" == closed ]]
}

self_test_readmit_not_ready_alerts() {
  self_test_setup readmit-not-ready
  self_test_color blue running closed "$SELF_TEST_OLD"
  self_test_color green stopped closed "$SELF_TEST_OLD"
  self_test_trait blue not_ready
  with_lock run_cycle >/dev/null 2>&1
  # 파일을 남기면 다음 주기가 열린 색으로 읽어 정상으로 넘어간다 — 다시 닫는다.
  self_test_events_are 'touch blue' 'rm blue' 'notify urgent' &&
    [[ "$(state_get '.hard_alert.reason')" == live/재투입실패 ]] &&
    [[ "$(color_state blue)" == closed ]]
}

self_test_starting_closed_color_waits() {
  self_test_setup starting-closed
  self_test_color blue running closed "$SELF_TEST_OLD"
  self_test_color green stopped closed "$SELF_TEST_OLD"
  self_test_trait blue starting
  with_lock run_cycle >/dev/null 2>&1
  # 알림도 없다 — 기동이 끝나면 다음 주기가 스모크한다.
  self_test_events_are &&
    [[ "$(color_state blue)" == closed ]]
}

self_test_unhealthy_closed_color_is_tried() {
  self_test_setup unhealthy-closed
  self_test_color blue running closed "$SELF_TEST_OLD"
  self_test_color green stopped closed "$SELF_TEST_OLD"
  self_test_trait blue unhealthy
  with_lock run_cycle >/dev/null 2>&1
  # 기다리는 것은 starting 하나다 — 비건강이면 스모크를 시도하고(9091 루트 DOWN) 실패를 알린다.
  self_test_events_are 'notify urgent' &&
    [[ "$(state_get '.hard_alert.reason')" == live/재투입실패 ]] &&
    [[ "$(color_state blue)" == closed ]]
}

self_test_both_closed_readmits_last_success() {
  self_test_setup both-closed
  self_test_color blue running closed "$SELF_TEST_STALE"
  self_test_color green running closed "$SELF_TEST_OLD"
  state_set '.last_success_ref = $ref' --arg ref "$SELF_TEST_OLD"
  with_lock run_cycle >/dev/null 2>&1
  # 두 색 모두 스모크를 통과할 수 있지만 마지막 성공 참조를 돌리는 green 만 연다.
  self_test_events_are 'touch green' 'notify urgent' &&
    [[ "$(color_state green)/$(color_state blue)" == open/closed ]]
}

self_test_both_closed_without_success_alerts() {
  self_test_setup both-closed-no-success
  self_test_color blue running closed "$SELF_TEST_STALE"
  self_test_color green running closed "$SELF_TEST_OLD"
  # 마지막 성공 참조를 어느 색도 돌리지 않는다.
  state_set '.last_success_ref = $ref' --arg ref "$SELF_TEST_NEW"
  with_lock run_cycle >/dev/null 2>&1
  self_test_events_are 'notify urgent' &&
    [[ "$(state_get '.hard_alert.reason')" == live/선택불가 ]] &&
    [[ "$(color_state blue)/$(color_state green)" == closed/closed ]]
}

self_test_both_open_is_left_alone() {
  self_test_setup both-open
  self_test_color blue running open "$SELF_TEST_OLD"
  self_test_color green running open "$SELF_TEST_NEW"
  # 두 주기를 돌려도 알림은 1건이다(일 1회 억제). 문구로 재투입 경로의 🚨 와 구분한다.
  with_lock run_cycle >/dev/null 2>&1
  with_lock run_cycle >/dev/null 2>&1
  self_test_events_are 'notify urgent' &&
    grep -q '두 색이 모두 열려' "$SELF_TEST_EVENTS"
}

self_test_exec_failure_skips_cycle() {
  self_test_setup exec-failure
  self_test_color blue running open "$SELF_TEST_OLD"
  self_test_color green running open "$SELF_TEST_NEW"
  self_test_trait green exec_fail
  with_lock run_cycle >/dev/null 2>&1
  # green 을 닫힘으로 읽으면 잔여로 보고 서비스 중인 green 을 멈춘다 — 알림 1건 말고는 아무것도 하지 않는다.
  self_test_events_are 'notify urgent' &&
    [[ "$(state_get '.hard_alert.reason')" == live/판정불가 ]]
}

self_test_inspect_failure_skips_cycle() {
  self_test_setup inspect-failure
  # green 은 컨테이너 자체가 없다(No such object → 멈춤).
  self_test_color blue running open "$SELF_TEST_NEW"
  self_test_trait blue inspect_fail
  with_lock run_cycle >/dev/null 2>&1
  # blue 를 멈춤으로 읽으면 부트스트랩으로 가 서비스 중인 blue 를 up·게이트 초기화한다.
  self_test_events_are 'notify urgent' || return 1
  [[ "$(state_get '.hard_alert.reason')" == live/판정불가 ]] || return 1
  # 데몬이 돌아오면 없는 green 은 멈춤으로 읽혀 정상(판정표 1행)이 되고 판정불가 억제가 풀린다.
  rm -f "$SELF_TEST_DOCKER/woni-api-blue/inspect_fail"
  with_lock run_cycle >/dev/null 2>&1
  self_test_events_are 'notify urgent' &&
    [[ -z "$(state_get '.hard_alert')" ]]
}

self_test_resolved_live_alert_is_cleared() {
  self_test_setup resolved-live-alert
  self_test_color blue running open "$SELF_TEST_NEW"
  self_test_color green stopped closed "$SELF_TEST_OLD"
  # live/ 가 아닌 사유(디스크)는 열린 색 하나로 해소되지 않는다 — 비우면 매 주기 다시 운다.
  state_set '.hard_alert = {reason: "disk", at: $v}' --argjson v "$(date +%s)"
  with_lock run_cycle >/dev/null 2>&1
  [[ "$(state_get '.hard_alert.reason')" == disk ]] || return 1
  state_set '.hard_alert = {reason: "live/둘다열림", at: $v}' --argjson v "$(date +%s)"
  with_lock run_cycle >/dev/null 2>&1
  [[ -z "$(state_get '.hard_alert')" ]] || return 1
  # 수동 정리 뒤 같은 날 다시 둘 다 열리면 접히지 않고 운다.
  self_test_color green running open "$SELF_TEST_OLD"
  with_lock run_cycle >/dev/null 2>&1
  self_test_events_are 'notify urgent' &&
    grep -q '두 색이 모두 열려' "$SELF_TEST_EVENTS"
}

self_test_leftover_closed_color_is_stopped() {
  self_test_setup leftover-closed
  self_test_color blue running open "$SELF_TEST_NEW"
  self_test_color green running closed "$SELF_TEST_OLD"
  with_lock run_cycle >/dev/null 2>&1
  self_test_events_are 'compose stop api-green'
}

self_test_leftover_stopped_then_deployed() {
  self_test_setup leftover-then-deploy
  self_test_color blue running open "$SELF_TEST_OLD"
  self_test_color green running closed "$SELF_TEST_OLD"
  with_lock run_cycle >/dev/null 2>&1
  # 잔여를 멈춘 같은 주기에 새 R 을 그 색으로 배포한다.
  self_test_events_are 'compose stop api-green' \
    'compose pull api-green' 'compose up -d --no-deps api-green' 'rm green' \
    'touch green' "sleep $GATE_SETTLE_SEC" 'rm blue' "sleep $GATE_SETTLE_SEC" \
    'edge /api/v1/assets' 'edge /api/v1/ledgers' 'edge /actuator/health' 'edge /api/v1/ledgers/import' 'edge /' \
    'compose stop api-blue' 'docker image prune -f' 'notify default' &&
    [[ "$(color_state green)/$(color_state blue)" == open/stopped ]]
}

self_test_hold_window_defers() {
  self_test_setup hold-window
  self_test_color blue running open "$SELF_TEST_OLD"
  self_test_color green stopped closed "$SELF_TEST_OLD"
  SELF_TEST_HHMM=1200
  with_lock run_cycle >/dev/null 2>&1
  # 보류 알림(⏸) 1건 말고는 아무 사건도 없다.
  self_test_events_are 'notify default' &&
    grep -q '보류 창' "$SELF_TEST_EVENTS"
}

self_test_missing_container_wording_both_versions() {
  local wording n=0
  # 운영 Docker 29.7.2 와 맥 Docker 28.3.2 의 '컨테이너 없음' 문구(2026-10-05 실측). 두 색 모두 없으면(컷오버 첫 배포)
  # 판정 불가가 아니라 부트스트랩으로 blue 에 배포한다.
  for wording in 'error: no such object' 'Error: No such object'; do
    n=$((n + 1))
    self_test_setup "missing-wording-$n"
    SELF_TEST_MISSING_WORDING=$wording
    with_lock run_cycle >/dev/null 2>&1
    self_test_events_are \
      'compose pull api-blue' 'compose up -d --no-deps api-blue' 'rm blue' 'touch blue' "sleep $GATE_SETTLE_SEC" \
      'edge /api/v1/assets' 'edge /api/v1/ledgers' 'edge /actuator/health' 'edge /api/v1/ledgers/import' 'edge /' \
      'docker image prune -f' 'notify default' || return 1
  done
}

self_test_run_case() {
  local name="$1" function_name="$2" rc
  SELF_TEST_CASES=$((SELF_TEST_CASES + 1))
  # 조건문 안에서 부르면 errexit 가 꺼져, 운영(main)에서는 스크립트를 죽일 실패가 시험에서는 조용히 지나간다 —
  # errexit 를 켠 서브셸로 돌린다.
  set +e
  (
    set -e
    "$function_name"
  )
  rc=$?
  set -e
  if ((rc == 0)); then
    printf 'PASS %s\n' "$name"
  else
    printf 'FAIL %s\n' "$name"
    SELF_TEST_FAILURES=$((SELF_TEST_FAILURES + 1))
  fi
}

self_test() {
  # 조용한 SKIP 은 검증 게이트를 거짓 통과시킨다 — jq 는 운영 경로와 같이 하드 요구한다.
  require_command jq || return 1
  SELF_TEST_ROOT=$(mktemp -d "${TMPDIR:-/tmp}/woni-deploy-agent-self-test.XXXXXX") || return 1
  SELF_TEST_CASES=0
  SELF_TEST_FAILURES=0
  SELF_TEST_HHMM=0300
  SELF_TEST_REMOTE_DIGEST="sha256:2222222222222222222222222222222222222222222222222222222222222222"
  SELF_TEST_OLD="$IMAGE_REPO@sha256:1111111111111111111111111111111111111111111111111111111111111111"
  SELF_TEST_NEW="$IMAGE_REPO@$SELF_TEST_REMOTE_DIGEST"
  SELF_TEST_STALE="$IMAGE_REPO@sha256:3333333333333333333333333333333333333333333333333333333333333333"
  NTFY_TOPIC=self-test
  SMOKE_HOST=smoke.invalid
  docker() { self_test_docker "$@"; }
  curl() { self_test_curl "$@"; }
  flock() { return 0; }
  sleep() { printf 'sleep %s\n' "$1" >>"$SELF_TEST_EVENTS"; }
  # 보류 창 판정만 고정하고 나머지(로그 시각·억제 타임스탬프)는 실제 date 를 쓴다.
  date() {
    if [[ "$*" == "+%H%M" ]]; then
      printf '%s\n' "$SELF_TEST_HHMM"
    else
      command date "$@"
    fi
  }
  # preflight_disk 가 시험 호스트의 디스크 여유에 흔들리지 않게 넉넉한 값으로 고정한다.
  df() { printf 'Filesystem 1M-blocks Used Available Capacity Mounted\nself-test 99999 0 99999 0%% /\n'; }

  self_test_run_case '전환 순서: 초기화 → 투입 → 철수 → 엣지 스모크 → 정지' self_test_switch_order
  self_test_run_case '반대 방향: green 이 살아 있으면 blue 로 배포' self_test_green_live_deploys_to_blue
  self_test_run_case 'override 는 L 의 실행 중 참조를 함께 적는다' self_test_override_keeps_live_ref
  self_test_run_case '멈춘 색에 남은 게이트는 up 직후 닫는다' self_test_stale_gate_closed_after_up
  self_test_run_case '게이트 초기화 실패는 T 만 멈춤' self_test_gate_init_failure_aborts
  self_test_run_case 'pull 실패는 override 두 값을 복원' self_test_pull_failure_restores_override
  self_test_run_case '직접 스모크 실패는 L 무변경 + 낙인' self_test_direct_smoke_failure_keeps_live
  self_test_run_case '게이트가 집계에 안 묶인 이미지 거부' self_test_ineffective_gate_refused
  self_test_run_case '직접 스모크 ledgers 가 401 이 아니면 거부' self_test_ledgers_not_401_refused
  self_test_run_case '게이트 투입 실패는 L 무변경' self_test_gate_open_failure_keeps_live
  self_test_run_case '투입 확인 실패는 L 을 닫지 않는다' self_test_target_not_ready_keeps_live
  self_test_run_case '게이트 철수 실패는 L 재투입' self_test_gate_close_failure_reverts
  self_test_run_case '엣지 스모크 값 불일치는 L 재투입 + 낙인' self_test_edge_failure_restores_live
  self_test_run_case '되돌리기 실패는 거기서 멈추고 🚨' self_test_revert_failure_alerts
  self_test_run_case '엣지 연결 실패는 되돌리지 않고 알림' self_test_edge_unreachable_keeps_target
  self_test_run_case '게이트 없는 이미지 거부' self_test_gateless_image_refused
  self_test_run_case 'healthy 미도달 크래시 루프는 L 무변경 + 낙인' self_test_unhealthy_target_keeps_live
  self_test_run_case '실행 중 참조 불일치는 T 만 멈춤 + 🚨' self_test_running_digest_mismatch_aborts
  self_test_run_case 'L 이 이미 R 이면 무변경' self_test_noop_when_live_runs_target
  self_test_run_case '무변경 주기가 성공 기록을 서비스 중인 참조로 맞춘다' self_test_noop_reconciles_last_success
  self_test_run_case '낙인된 R 은 성공 기록으로 맞추지 않는다' self_test_noop_skips_branded_reconcile
  self_test_run_case '부트스트랩은 철수 생략' self_test_bootstrap_without_live
  self_test_run_case '부트스트랩 엣지 값 불일치는 T 를 그대로 둔다' self_test_bootstrap_edge_failure_keeps_target
  self_test_run_case '닫힌 단일 색 재투입' self_test_closed_single_color_is_readmitted
  self_test_run_case '재투입 전 직접 스모크 실패는 닫힌 채 둔다' self_test_readmit_smoke_failure_keeps_closed
  self_test_run_case '재투입 뒤 readiness 미확인은 다시 닫고 🚨' self_test_readmit_not_ready_alerts
  self_test_run_case '기동 중인 닫힌 색은 기다린다' self_test_starting_closed_color_waits
  self_test_run_case '비건강한 닫힌 색은 스모크를 시도한다' self_test_unhealthy_closed_color_is_tried
  self_test_run_case '닫힌 두 색은 마지막 성공 참조 쪽만 재투입' self_test_both_closed_readmits_last_success
  self_test_run_case '닫힌 두 색에서 고를 수 없으면 🚨 만' self_test_both_closed_without_success_alerts
  self_test_run_case '두 색 모두 열림은 알림만' self_test_both_open_is_left_alone
  self_test_run_case '게이트 판정 실패는 그 주기를 건너뛴다' self_test_exec_failure_skips_cycle
  self_test_run_case 'inspect 실패도 판정 불가' self_test_inspect_failure_skips_cycle
  self_test_run_case '열린 색이 하나로 돌아오면 live 알림 억제를 푼다' self_test_resolved_live_alert_is_cleared
  self_test_run_case '닫힌 잔여 색 정지' self_test_leftover_closed_color_is_stopped
  self_test_run_case '잔여 정지 뒤 같은 주기에 배포' self_test_leftover_stopped_then_deployed
  self_test_run_case '보류 창은 미룸' self_test_hold_window_defers
  self_test_run_case '컨테이너 없음 문구는 두 Docker 버전 모두 멈춤' self_test_missing_container_wording_both_versions

  rm -rf -- "$SELF_TEST_ROOT"
  printf '%d건 실행, 실패 %d건\n' "$SELF_TEST_CASES" "$SELF_TEST_FAILURES"
  # 0건 실행이 통과하지 않게 케이스 수를 못박는다 — 케이스 줄을 지워도 여기서 빨개진다.
  ((SELF_TEST_CASES == 38 && SELF_TEST_FAILURES == 0))
}

main() {
  local mode=cycle unit=""
  case "${1:-}" in
    "" | --once) ;;
    --dry-run) mode=dry-run ;;
    --clear-failed) mode=clear-failed ;;
    --smoke-only) mode=smoke-only ;;
    --self-test) mode=self-test ;;
    --notify-failure)
      mode=notify-failure
      unit="${2:-}"
      ;;
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
  if [[ "$mode" == notify-failure ]]; then
    if (($# != 2)) || [[ -z "$unit" ]]; then
      usage >&2
      return 1
    fi
  elif (($# > 1)); then
    usage >&2
    return 1
  fi

  if [[ "$mode" == self-test ]]; then
    self_test
    return
  fi

  load_config || return 1
  require_command curl || return 1
  if [[ "$mode" == notify-failure ]]; then
    # 이 모드는 알림이 유일한 일이라 전송 실패를 그대로 비-0 으로 드러낸다(systemctl status 에 남는다).
    # 인지된 한계: 사유가 지속되면 300초마다 🔴 가 반복된다. 억제는 구조적으로 완결되지 않는다 —
    # 가장 흔한 유닛 실패 원인인 agent.conf 부재·오류는 토픽 자체를 모르게 만들어 알림이 아예 불가능하다.
    notify "🔴 유닛 실패: $unit" || return 1
    return 0
  fi

  require_command jq || return 1
  require_command docker || return 1
  case "$mode" in
    dry-run)
      DRY_RUN=true
      run_cycle
      ;;
    clear-failed) with_lock clear_failed ;;
    smoke-only) with_lock run_smoke_only ;;
    *) with_lock run_cycle ;;
  esac
}

trap cleanup EXIT
main "$@"

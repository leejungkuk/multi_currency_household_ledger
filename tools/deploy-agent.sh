#!/usr/bin/env bash
# woni 배포 에이전트 — GHCR 의 :deploy 태그를 폴링해 배포·스모크·롤백까지 한다.
# 설계 SSOT 는 .claude/plan/cicd-deploy-plan.md §4 다. systemd timer(300초)가 인자 없이 호출한다.
# Linux 호스트 전용이다(flock). macOS 에서는 bash -n·shellcheck·--dry-run 만 돈다.
#
# 알림 억제(플랜 §9 에서 미정으로 남긴 항목 — 여기서 확정): 미해소 상태를 종류별 채널로 나누지 않고
# **일 1회**로 묶는다(m9). ① 낙인·그룹 B 미해소는 stale_reminder_at 으로 요약 1건 ② 하드 실패(디스크·
# override 교체·pull·기동/스모크 실패)는 사람이 고칠 때까지 300초마다 그대로 재발하므로 hard_alert 로
# 같은 사유당 1일 1건 — 사유가 바뀌면 즉시 울리고, 성공 배포가 hard_alert 를 비워 조건이 해소되면
# 다음 발생 때 다시 울린다. 사유키에는 digest 와 낙인 여부를 함께 넣어 새 이미지의 첫 실패와 낙인
# 전환이 접히지 않게 한다. 나머지는 조회 실패 3회 연속(=15분)에서 1회만, 보류는 참조당 1회.

# jq 필터의 $d·$v 는 --arg 로 넘기는 jq 변수라 셸이 확장하면 안 된다(SC2016 은 이 파일에서 전부 그 경우다).
# shellcheck disable=SC2016

set -euo pipefail

readonly IMAGE_PATH="leejungkuk/multi_currency_household_ledger"
readonly IMAGE_REPO="ghcr.io/$IMAGE_PATH"
readonly DEPLOY_TAG="deploy"
readonly SERVICE="api"
readonly CONTAINER="woni-api"
readonly CADDY_CONTAINER="woni-caddy"
readonly ROLLBACK_TAG="woni-api:rollback"
# Dockerfile:42 가 start-period=120s interval=30s retries=3 이라 unhealthy 확정까지 최악 210초이고,
# JWKS 콜드 스타트로 1회 재시작하면 start-period 가 다시 돈다. 롤백 후 복구 판정에도 같은 유예를 쓴다.
readonly HEALTH_TIMEOUT_SEC=420
readonly HEALTH_POLL_SEC=10
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
  --dry-run                  판정까지만. docker·ntfy·상태파일에 쓰지 않는다
  --clear-failed             failed_digests 비우기
  --smoke-only               배포 없이 스모크만. 롤백하지 않고 보고만 한다
  --notify-failure <unit>    OnFailure= 전용. 알림 1건 보내고 종료
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

# 없으면 빈 문자열 = base 의 ${WONI_IMAGE_TAG:-latest} 로 열화한 상태(S20).
override_ref() {
  [[ -f "$OVERRIDE_FILE" ]] || return 0
  grep -oE '[^[:space:]"]+@sha256:[0-9a-f]{64}' "$OVERRIDE_FILE" | head -n 1 || true
}

# RepoDigests 2단 inspect 는 값이 비면 템플릿 에러, 여럿이면 오답이라 쓰지 않는다.
container_image_ref() { docker inspect "$CONTAINER" --format '{{.Config.Image}}' 2>/dev/null || true; }

# HEALTHCHECK 없는 이미지는 .State.Health 자체가 없어 방어하지 않으면 템플릿이 exit 1 로 죽는다.
container_health() {
  docker inspect "$CONTAINER" --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' 2>/dev/null ||
    printf 'absent\n'
}

container_restart_count() { docker inspect "$CONTAINER" --format '{{.RestartCount}}' 2>/dev/null || printf '0\n'; }

container_revision() {
  local rev
  rev=$(docker inspect "$CONTAINER" --format '{{index .Config.Labels "org.opencontainers.image.revision"}}' 2>/dev/null) || return 0
  [[ "$rev" =~ ^[0-9a-f]{40}$ ]] && printf '%s\n' "$rev"
  return 0
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
write_override() {
  local ref="$1" temp staged
  # staged 도 new_temp 로 만든다 — mv 전에 죽어도 EXIT 정리가 지운다(직접 이름을 지으면 남는다).
  new_temp temp "$DEPLOY_DIR" || return 1
  new_temp staged "$DEPLOY_DIR" || return 1
  {
    echo "# tools/deploy-agent.sh 가 소유한다. 사람이 편집하지 않는다."
    echo "services:"
    echo "  $SERVICE:"
    echo "    image: $ref"
  } >"$temp" || return 1
  if install -m 644 "$temp" "$staged" && mv -f "$staged" "$OVERRIDE_FILE"; then
    rm -f "$temp"
    return 0
  fi
  rm -f "$staged"
  return 1
}

restore_override() {
  if [[ -n "$1" ]]; then
    write_override "$1"
  else
    rm -f "$OVERRIDE_FILE"
  fi
}

wait_healthy() {
  local waited=0
  while ((waited < HEALTH_TIMEOUT_SEC)); do
    if [[ "$(container_health)" == healthy ]]; then
      return 0
    fi
    sleep "$HEALTH_POLL_SEC"
    waited=$((waited + HEALTH_POLL_SEC))
  done
  [[ "$(container_health)" == healthy ]]
}

# 스모크는 로컬 caddy 를 직접 친다 — 인증서·SNI·Caddyfile 을 그대로 검증하면서 외부 왕복 의존을 없앤다.
edge_curl() {
  local out="$1"
  shift
  curl -sS -m 20 -o "$out" -w '%{http_code}' --resolve "$SMOKE_HOST:443:127.0.0.1" "$@"
}

# 0=통과 / 1=값 불일치(이미지 유래) / 2=엣지 연결 실패 / 3=로컬 준비 실패. 2·3 은 호스트 유래라 롤백도
# 낙인도 하지 않는다 — ②③ 은 로컬 caddy 를 경유하므로 caddy 다운·TLS 만료(S22)를 이미지 탓으로 돌리면
# 정상 이미지가 --clear-failed 전까지 영구 스킵된다(D5, 그룹 B 와 같은 결).
# ① 은 docker exec 라 엣지를 거치지 않으므로 실패하면 이미지 유래다.
smoke_group_a() {
  local out status mismatch=false conn_fail=false
  new_temp out || return 3
  if ! docker exec "$CONTAINER" curl -fsS http://localhost:9091/actuator/health 2>/dev/null |
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

fail_and_rollback() {
  local digest="$1" brand="$2" reason="$3" urgent="${4:-false}" rollback_ref running_ref prefix=⚠️
  # 낙인하지 않는 실패는 롤백이 override 를 되돌려 다음 주기에 R ≠ C 가 다시 성립하므로 같은 배포를
  # 그대로 재시도한다. 종료 조건이 없으면 배포↔롤백이 5분마다 영원히 반복되며(컨테이너가 매번 재생성돼
  # 서비스가 플래핑하는데 알림은 접혀 조용하다), 그래서 플랜 §4 규칙 2 의 "같은 R 연속 2회 → 낙인"을
  # 호출부가 아니라 여기서 판정한다 — brand=false 로 들어오는 모든 경로(기동 실패 포함)가 함께 끊긴다.
  if [[ "$brand" != true && "$(state_get '.last_failed_ref')" == "$digest" ]]; then
    brand=true
  fi
  if [[ "$brand" == true ]]; then
    mark_failed "$digest"
    # 낙인 여부를 사유에 담는다 — 1회차 ⚠️ 뒤 2회차에서 낙인으로 전환돼도 사유키가 같으면 "CD 가 멈췄고
    # --clear-failed 가 필요하다"는 상태 변화가 통째로 접힌다.
    reason="$reason/낙인(해소 후 --clear-failed)"
  else
    state_set '.last_failed_ref = $d' --arg d "$digest"
  fi
  rollback_ref=$(state_get '.last_success_ref')
  if [[ -z "$rollback_ref" ]]; then
    notify_hard "$reason/무롤백" "$digest" "🚨 $reason — 롤백 대상이 없습니다 $(short "$digest")"
    return 0
  fi
  log "롤백합니다: $(short "${rollback_ref##*@}")"
  # 긴급 승격은 호출부가 지정한 경로만 받는다 — 공용 문구를 🚨 로 바꾸면 정상 크래시 루프 롤백까지 승격돼 🚨 가 흔해진다.
  [[ "$urgent" == true ]] && prefix=🚨
  # 낙인하지 않는 실패(기동 실패·healthy 미도달)는 다음 주기에 같은 배포를 그대로 재시도하므로 사유별 억제에 태운다.
  # healthy 만으로 "롤백 완료"를 단언하지 않는다 — 롤백도 override 로 하므로 override 가 안 먹히는 상황이면
  # 롤백 역시 안 먹힌 채 실패한 이미지가 그대로 healthy 여서 거짓 성공 보고가 나간다. 실행 중 참조로 실검증한다.
  if write_override "$rollback_ref" && compose up -d "$SERVICE" && wait_healthy &&
    running_ref=$(container_image_ref) && [[ "${running_ref##*@}" == "${rollback_ref##*@}" ]]; then
    notify_hard "$reason" "$digest" "$prefix $reason → 롤백 완료 $(short "${rollback_ref##*@}") (실패 $(short "$digest"))"
  else
    notify_hard "$reason/롤백실패" "$digest" "🚨 $reason → 롤백까지 실패했습니다. 수동 개입 후 --clear-failed 를 실행하세요."
  fi
}

deploy() {
  local target_ref="$1" target_digest="$2" previous_ref="$3" previous_override_ref="$4"
  local restart_baseline restarts running_digest group_a_rc=0 group_b_rc=0 brand=false soft="" a_label="" label rev
  SECONDS=0

  preflight_disk || return 0
  if ! write_override "$target_ref"; then
    notify_hard override "$target_digest" "🚨 이미지 참조 파일 교체에 실패했습니다 $(short "$target_digest")"
    return 0
  fi

  log "배포를 시작합니다: $(short "$target_digest")"
  # caddy 를 무인 업그레이드하면 Caddyfile 방어(2.11.x 실측 기반)가 조용히 깨지므로 api 만 pull 한다.
  if ! compose pull "$SERVICE"; then
    # 되돌려야 override 와 실행 중 컨테이너가 다시 일치한다 — 배포는 없었는데 참조만 앞서간 상태로 두지
    # 않는다. 되돌리기까지 실패하면 사유를 나눠 알린다: "pull 실패" 만 나가면 운영자가 참조가 온전하다고
    # 오해하는데, 사유키가 다르므로 억제를 뚫고 즉시 울린다.
    if restore_override "$previous_override_ref"; then
      notify_hard pull "$target_digest" "🚨 이미지 pull 에 실패했습니다 $(short "$target_digest")"
    else
      notify_hard pull/되돌리기실패 "$target_digest" \
        "🚨 이미지 pull 실패 후 참조 되돌리기까지 실패했습니다 $(short "$target_digest")"
    fi
    return 0
  fi
  if ! compose up -d "$SERVICE"; then
    fail_and_rollback "$target_digest" false "기동 실패"
    return 0
  fi

  restart_baseline=$(container_restart_count)
  if ! wait_healthy; then
    restarts=$(container_restart_count)
    # 크래시 루프는 RestartCount 가 늘고, 의존성 장애·기동 지연은 불변이다(플랜 §4 규칙 2).
    # 연속 2회 실패의 낙인 승격은 fail_and_rollback 이 공통으로 판정한다.
    if ((restarts > restart_baseline)); then
      brand=true
    fi
    fail_and_rollback "$target_digest" "$brand" "420초 내 healthy 미도달"
    return 0
  fi

  running_digest=$(container_image_ref)
  running_digest="${running_digest##*@}"
  # 알림만 내고 끝내면 종료 조건이 없다 — 트리거가 실행 중 digest 를 요구하므로 단언이 실패하는 한
  # 매 주기 트리거가 다시 성립해 하루 288회 재생성 + 288건 🚨 가 된다. fail_and_rollback 에 태워
  # ① 사유별 억제 ② 연속 2회 → 낙인으로 끊는다. up -d 가 이미 target 으로 재생성했는데도 참조가
  # 다르면 재배포로 고칠 것이 없으니(B2 와 같은 논리) 반복을 멈추는 쪽이 맞다.
  # 이 경로만 긴급(4번째 인자)이다 — 플랜 §4 단언 「실행 중 참조 == R (아니면 성공 보고 금지 + 🚨)」.
  if [[ "$running_digest" != "$target_digest" ]]; then
    fail_and_rollback "$target_digest" false "실행 중 참조 불일치" true
    return 0
  fi

  smoke_group_a || group_a_rc=$?
  case "$group_a_rc" in
    0) ;;
    1)
      # 값 불일치만 1회로 낙인한다(플랜 §3 mermaid `M -->|실패| R1["롤백 + 낙인"]`) — 응답값이 틀린
      # 이미지는 재배포로 고쳐지지 않는다. A①·A② 는 DB 를 타므로 wait_healthy 통과 직후 Supabase 가
      # 끊기면 정상 이미지도 낙인되지만, 그 창은 두 검사 사이 수초로 좁고 --clear-failed 로 해소된다.
      fail_and_rollback "$target_digest" true "스모크 그룹 A 응답 불일치"
      return 0
      ;;
    2) a_label=그룹A엣지연결 ;;
    *) a_label=그룹A준비실패 ;;
  esac

  # 그룹 A 의 호스트 유래 실패는 A①(docker exec 내부 actuator)이 통과한 뒤에만 나온다 — 앱은 정상이고
  # 엣지·호스트만 안 되는 것이라 구 이미지로 되돌려도 같은 caddy 를 거쳐 나아지는 것이 없다. 그래서
  # 롤백도 낙인도 하지 않고 알림만 낸다(D5·S22). 배포를 그대로 두면 다음 주기가 R == C 로 스킵해
  # 배포↔롤백이 5분마다 영원히 반복되지 않는다.
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

  # digest 로만 pull 하면 구 이미지는 태그가 없어 prune 에 dangling 으로 지워지고, 그러면 롤백이
  # 항상 GHCR 재pull 을 요구한다(사고 시점에 GHCR 이 안 되면 롤백 불가) — 규칙 8.
  if [[ -n "$previous_ref" ]]; then
    docker tag "$previous_ref" "$ROLLBACK_TAG" >/dev/null 2>&1 || warn "직전 이미지 태깅에 실패했습니다."
  fi
  # 그룹 A·B 의 호스트 유래 실패는 이미지 승격을 막지 않는다(D5). 막으면 Caddyfile 미해소가 롤백 대상을
  # 영구 동결시켜 "직전 이미지로 되돌린다"는 §7 의 expand/contract 전제가 깨진다. 규칙 1 의 "스모크까지
  # 통과했을 때만"이 겨냥하는 것은 이미지 유래 실패(그룹 A 값 불일치)이고 그 경로는 위에서 롤백해 여기에
  # 닿지 않는다.
  # hold_notified_ref 도 함께 비운다 — 같은 참조가 나중에 다시 보류되면 ⏸ 가 다시 울려야 한다.
  # hard_alert 도 비운다 — 배포가 끝까지 성공했으면 하드 실패 조건이 해소된 것이고, 다시 생기면 즉시 울려야 한다.
  state_set '.last_success_ref = $ref | .last_failed_ref = "" | .hold_notified_ref = "" | .hard_alert = null' \
    --arg ref "$target_ref"
  docker image prune -f >/dev/null 2>&1 || true

  label=$(short "$target_digest")
  rev=$(container_revision)
  [[ -n "$rev" ]] && label="sha=${rev:0:7} $label"
  if ((group_b_rc == 2)); then
    notify "🚨 배포는 됐으나 스모크가 실패했습니다 $label ${SECONDS}s${GROUPB_LABELS:+ (${GROUPB_LABELS% })}" || true
  else
    notify "✅ 배포 완료 $label ${SECONDS}s$soft" || true
  fi
}

run_cycle() {
  local remote_digest="" remote_ref previous_override_ref previous_ref probe_rc=0
  state_init || return 1

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
  previous_override_ref=$(override_ref)
  previous_ref=$(container_image_ref)

  # 트리거는 "R ≠ C 또는 컨테이너가 R 로 돌고 있지 않음" 이다. 비-healthy 는 여전히 트리거가 아니다 —
  # 설정이 같으면 up -d 가 재생성하지 않아 아무것도 못 고치면서 유예만 태운다(실측, S17). 여기서 보는
  # 것은 건강 상태가 아니라 참조 불일치이고, 실제로 설정이 다르므로 up -d 가 재생성한다.
  # 실행 중 참조를 존재 여부로만 쓰면 write_override 와 up -d 사이에 리부팅·OOM 이 끼었을 때
  # (그 사이 대부분은 pull 이고 구 컨테이너는 살아 있다) override 만 새 digest 로 앞서간 채
  # restart: unless-stopped 가 구 이미지 컨테이너를 되살려, 다음 주기가 R == C 로 보고 영구히 건너뛴다.
  # 컨테이너 부재(빈 문자열)·태그로 뜬 컨테이너(S20 열화, @ 가 없어 전체 문자열이 남는다)도 같은 항이 잡는다.
  if [[ "$remote_digest" == "${previous_override_ref##*@}" && "$remote_digest" == "${previous_ref##*@}" ]]; then
    log "변화가 없습니다($(short "$remote_digest")). 배포하지 않습니다."
    daily_reminder "$remote_digest"
    return 0
  fi
  if is_failed_digest "$remote_digest"; then
    log "낙인된 digest 라 배포하지 않습니다($(short "$remote_digest"))."
    daily_reminder "$remote_digest"
    return 0
  fi
  if is_hold_window; then
    log "보류 창(10:40~14:05 KST)이라 배포를 미룹니다($(short "$remote_digest"))."
    notify_hold_once "$remote_digest"
    return 0
  fi

  if [[ "$DRY_RUN" == true ]]; then
    local container_state=부재
    [[ -n "$previous_ref" ]] && container_state=있음
    log "판정: 배포 대상 $(short "$remote_digest") (현재 참조 $(short "${previous_override_ref##*@}"), 컨테이너 $container_state)"
    return 0
  fi
  deploy "$remote_ref" "$remote_digest" "$previous_ref" "$previous_override_ref"
}

clear_failed() {
  state_init || return 1
  # hard_alert 도 비운다 — 손으로 낙인을 푸는 것은 성공 배포와 같은 명시적 해소 신호다. 남겨 두면 개입
  # 직후 같은 digest 가 같은 사유로 재실패했을 때 사유키가 같아 24시간 접히고, 플래핑이 무음이 된다.
  state_set '.failed_digests = [] | .last_failed_ref = "" | .hard_alert = null'
  log "failed_digests 를 비웠습니다."
}

run_smoke_only() {
  local rc=0 group_a_rc=0 group_b_rc=0
  state_init || return 1
  smoke_group_a || group_a_rc=$?
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
  log "보고 전용 모드라 롤백하지 않습니다."
  return "$rc"
}

main() {
  local mode=cycle unit=""
  case "${1:-}" in
    "" | --once) ;;
    --dry-run) mode=dry-run ;;
    --clear-failed) mode=clear-failed ;;
    --smoke-only) mode=smoke-only ;;
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

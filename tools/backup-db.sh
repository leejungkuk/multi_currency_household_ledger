#!/usr/bin/env bash
# woni 운영 DB 백업 — pg_dump → age 암호화 → OCI Object Storage 업로드.
# systemd timer(woni-backup.timer)가 인자 없이 호출한다. Linux 호스트 전용이다(docker·age).
# macOS 에서는 bash -n·shellcheck·--dry-run 만 돈다.
#
# 설계 근거(DEPLOY.md §DB 백업):
#   - Supabase 무료 티어에는 자동 백업이 없다. 이 스크립트가 유일한 복구 수단이다.
#   - **암호화는 공개키(age recipient)로만 한다.** 개인키는 서버에 없으므로 이 호스트가 털려도
#     과거 백업을 읽지 못한다. 대칭키 암호를 쓰면 이 성질이 사라진다.
#   - **평문 덤프를 디스크에 남기지 않는다.** pg_dump 를 age 로 바로 파이프한다 — 임시 파일로
#     받으면 그 파일이 존재하는 동안 전 이용자의 금융 데이터가 호스트에 평문으로 놓인다.
#   - **업로드 자격증명은 쓰기 전용 PAR(AnyObjectWrite) 하나다.** 목록·읽기·삭제 권한이 없어
#     털려도 과거 백업을 읽거나 지우지 못한다. 그 대신 **세대 정리를 서버가 할 수 없으므로**
#     보관 기간은 버킷의 lifecycle 규칙이 담당한다(daily 7일 · weekly 28일 · monthly 93일).
#     그래서 이 스크립트는 날짜에 따라 prefix 만 고른다.
#   - "돌고 있다고 믿는 백업"이 최악이므로 **성공도 흔적을 남긴다** — node_exporter textfile
#     메트릭에 마지막 성공 시각을 쓰고, Grafana 가 그 값이 낡으면 알린다. ntfy 는 실패에만 쓴다
#     (매일 성공 알림은 소음이 되어 결국 무시된다).

set -euo pipefail

readonly PG_IMAGE="postgres:17-alpine"
# 업로드 결과가 이보다 작으면 성공으로 보지 않는다. 스키마만 있어도 수 KB 는 나오므로,
# 이 값 아래는 pg_dump 가 조기 종료했거나 age 가 빈 입력을 감쌌다는 뜻이다.
readonly MIN_BACKUP_BYTES=4096
readonly CURL_MAX_SEC=600
# node_exporter 가 --collector.textfile.directory 로 읽는 곳(monitoring/docker-compose.yml).
# StateDirectory=woni-metrics 가 이 경로를 서비스 사용자 소유로 만들어 준다.
readonly METRIC_DIR="/var/lib/woni-metrics"
readonly METRIC_FILE="$METRIC_DIR/woni_backup.prom"

DRY_RUN=false
TEMPS=()

log() { printf '%s %s\n' "$(TZ=Asia/Seoul date '+%F %T%z')" "$*"; }
warn() { log "$*" >&2; }

cleanup() {
  ((${#TEMPS[@]} > 0)) && rm -f "${TEMPS[@]}"
  return 0
}
trap cleanup EXIT

# $1=경로를 담을 변수명. 값을 표준출력으로 돌려주면 명령치환 서브셸이 되어 TEMPS 등록이 사라지고
# EXIT 정리가 아무것도 지우지 못한다(deploy-agent.sh 와 같은 이유).
new_temp() {
  local __dir="${TMPDIR:-/tmp}" __path
  __path=$(mktemp "${__dir%/}/.woni-backup.XXXXXX") || return 1
  TEMPS+=("$__path")
  printf -v "$1" '%s' "$__path"
}

usage() {
  cat <<'EOF'
사용법: tools/backup-db.sh [모드]
  (없음)                     1회 백업 (systemd 가 호출)
  --dry-run                  덤프·암호화까지만 하고 업로드·메트릭·ntfy 를 건너뛴다
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

# .env 한 줄에서 값만 꺼낸다. compose 의 env_file 은 따옴표를 값의 일부로 보지만, 사람이
# 손으로 넣다 감싸는 경우가 있어 양끝 따옴표는 벗긴다.
env_value() {
  local file="$1" key="$2" v
  v=$(grep -m1 "^${key}=" "$file" | cut -d= -f2-) || return 0
  v="${v%$'\r'}"
  if [[ "$v" == \"*\" || "$v" == \'*\' ]]; then v="${v:1:${#v}-2}"; fi
  printf '%s' "$v"
}

# 설정 값(PAR URL·DB 비밀번호)은 로그·알림에 절대 출력하지 않는다(deploy-agent.sh 와 같은 규칙).
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
  PAR_URL="${WONI_BACKUP_PAR_URL:-}"
  AGE_RECIPIENT="${WONI_BACKUP_AGE_RECIPIENT:-}"
  if [[ -z "$DEPLOY_DIR" || -z "$NTFY_TOPIC" || -z "$PAR_URL" || -z "$AGE_RECIPIENT" ]]; then
    echo "WONI_DEPLOY_DIR·WONI_NTFY_TOPIC·WONI_BACKUP_PAR_URL·WONI_BACKUP_AGE_RECIPIENT 가 모두 있어야 합니다: $conf" >&2
    return 1
  fi
  # PAR URL 은 반드시 / 로 끝나야 뒤에 객체 이름을 이어 붙일 수 있다. 끝 슬래시를 빠뜨리면
  # 업로드가 매번 같은 이름 하나를 덮어써 세대가 통째로 사라진다.
  [[ "$PAR_URL" == */ ]] || PAR_URL="$PAR_URL/"
}

# DB 접속 정보는 앱과 같은 정본(deploy/.env)에서 읽는다 — 두 곳에 두면 비밀번호 회전 때 어긋난다.
load_db_credentials() {
  local envfile="$DEPLOY_DIR/.env" url
  if [[ ! -r "$envfile" ]]; then
    echo "DB 자격증명 파일을 읽을 수 없습니다: $envfile" >&2
    return 1
  fi
  url=$(env_value "$envfile" SPRING_DATASOURCE_URL)
  PGUSER_VALUE=$(env_value "$envfile" SPRING_DATASOURCE_USERNAME)
  # export 한다 — docker 에 `-e KEY=값` 으로 넘기면 그 값이 호스트 ps 출력에 그대로 보인다.
  # `--env KEY`(값 없음)는 현재 환경에서 가져가므로 명령줄에 남지 않는다.
  export PGPASSWORD
  PGPASSWORD=$(env_value "$envfile" SPRING_DATASOURCE_PASSWORD)
  if [[ -z "$url" || -z "$PGUSER_VALUE" || -z "$PGPASSWORD" ]]; then
    echo "SPRING_DATASOURCE_URL·USERNAME·PASSWORD 를 $envfile 에서 찾지 못했습니다" >&2
    return 1
  fi
  # jdbc:postgresql://host:port/db[?params] 에서 host·port·db 를 뽑는다.
  if [[ ! "$url" =~ ^jdbc:postgresql://([^:/?]+):([0-9]+)/([^?]+) ]]; then
    echo "SPRING_DATASOURCE_URL 형식을 해석하지 못했습니다(jdbc:postgresql://host:port/db 형태여야 합니다)" >&2
    return 1
  fi
  PGHOST_VALUE="${BASH_REMATCH[1]}"
  PGPORT_VALUE="${BASH_REMATCH[2]}"
  PGDATABASE_VALUE="${BASH_REMATCH[3]}"
  # transaction pooler(:6543)는 prepared statement 와 충돌해 pg_dump 가 중간에 깨진다.
  # 앱과 같은 이유로 session pooler(:5432)만 받는다(DEPLOY.md §환경변수).
  if [[ "$PGPORT_VALUE" == "6543" ]]; then
    echo "transaction pooler(:6543)로는 pg_dump 를 뜨지 않습니다. session pooler(:5432)를 쓰세요" >&2
    return 1
  fi
}

notify() {
  local message="$1" priority="${2:-default}"
  if [[ "$DRY_RUN" == true ]]; then
    log "[dry-run] 알림 생략"
    return 0
  fi
  # 토픽이 curl 에러 문구로 새지 않도록 출력을 통째로 버린다.
  curl -sS -m 10 -H "Priority: $priority" -d "$message" "https://ntfy.sh/$NTFY_TOPIC" >/dev/null 2>&1 && return 0
  warn "ntfy 전송에 실패했습니다."
  return 1
}

# 날짜로 세대를 고른다. 하루 한 벌만 올리고 어느 prefix 에 넣을지만 바꾼다 — 버킷 lifecycle 이
# prefix 별로 다른 보관 기간을 적용하므로 이것만으로 일7·주4·월3 이 성립한다.
select_prefix() {
  local dom dow
  dom=$(TZ=Asia/Seoul date '+%d')
  dow=$(TZ=Asia/Seoul date '+%u') # 1=월 … 7=일
  if [[ "$dom" == "01" ]]; then
    printf 'monthly'
  elif [[ "$dow" == "7" ]]; then
    printf 'weekly'
  else
    printf 'daily'
  fi
}

# node_exporter textfile 컬렉터가 읽는다. 원자적 교체를 하지 않으면 컬렉터가 반쯤 쓰인 파일을
# 읽어 파싱 오류를 낸다.
write_metrics() {
  local ok="$1" epoch="$2" bytes="$3" tmp
  [[ "$DRY_RUN" == true ]] && return 0
  if ! mkdir -p "$METRIC_DIR" 2>/dev/null; then
    warn "메트릭 디렉터리를 만들지 못했습니다: $METRIC_DIR"
    return 0
  fi
  tmp=$(mktemp "$METRIC_DIR/.woni_backup.XXXXXX") || return 0
  TEMPS+=("$tmp")
  {
    echo "# HELP woni_backup_last_success_timestamp_seconds 마지막으로 성공한 DB 백업의 유닉스 시각."
    echo "# TYPE woni_backup_last_success_timestamp_seconds gauge"
    echo "woni_backup_last_success_timestamp_seconds $epoch"
    echo "# HELP woni_backup_last_size_bytes 마지막으로 성공한 DB 백업의 암호화 후 크기."
    echo "# TYPE woni_backup_last_size_bytes gauge"
    echo "woni_backup_last_size_bytes $bytes"
    echo "# HELP woni_backup_last_run_success 마지막 실행의 성공 여부(1=성공, 0=실패)."
    echo "# TYPE woni_backup_last_run_success gauge"
    echo "woni_backup_last_run_success $ok"
  } >"$tmp"
  chmod 644 "$tmp"
  mv -f "$tmp" "$METRIC_FILE"
}

# 실패해도 마지막 성공 시각은 보존한다 — 그 값이 지워지면 Grafana 의 "백업이 낡았다" 경보가
# No Data 로 바뀌어, 정작 위험할 때 조용해진다.
mark_failure() {
  local prev_epoch=0 prev_bytes=0
  if [[ -r "$METRIC_FILE" ]]; then
    prev_epoch=$(awk '/^woni_backup_last_success_timestamp_seconds /{print $2}' "$METRIC_FILE")
    prev_bytes=$(awk '/^woni_backup_last_size_bytes /{print $2}' "$METRIC_FILE")
  fi
  write_metrics 0 "${prev_epoch:-0}" "${prev_bytes:-0}"
}

run_backup() {
  local prefix object enc err size epoch http
  prefix=$(select_prefix)
  object="$prefix/woni-$(TZ=Asia/Seoul date '+%Y%m%dT%H%M%S%z').sql.age"

  new_temp enc
  new_temp err

  # pg_dump 는 컨테이너로 돌린다 — 호스트에 postgres-client 를 깔지 않고, 버전이 이미지에 고정돼
  # 서버 패키지 업그레이드로 덤프 형식이 바뀌지도 않는다.
  # --no-owner/--no-acl: 복원 대상 롤이 달라도 붙는다(Supabase 는 프로젝트마다 롤 구성이 다르다).
  # 파이프이므로 set -o pipefail 이 pg_dump 실패를 잡는다. 평문은 디스크에 닿지 않는다.
  log "덤프를 뜹니다: $PGHOST_VALUE:$PGPORT_VALUE/$PGDATABASE_VALUE → $object"
  if ! docker run --rm --env PGPASSWORD "$PG_IMAGE" \
    pg_dump --host="$PGHOST_VALUE" --port="$PGPORT_VALUE" \
    --username="$PGUSER_VALUE" --dbname="$PGDATABASE_VALUE" \
    --no-owner --no-acl --format=plain 2>"$err" |
    age -r "$AGE_RECIPIENT" -o "$enc"; then
    warn "덤프 또는 암호화가 실패했습니다: $(tail -3 "$err" | tr '\n' ' ')"
    return 1
  fi

  size=$(wc -c <"$enc" | tr -d ' ')
  if ((size < MIN_BACKUP_BYTES)); then
    warn "백업이 비정상적으로 작습니다(${size}B < ${MIN_BACKUP_BYTES}B). 업로드하지 않습니다."
    return 1
  fi

  if [[ "$DRY_RUN" == true ]]; then
    log "[dry-run] 업로드 생략 — object=$object size=${size}B"
    return 0
  fi

  # PAR 는 쓰기 전용이라 업로드 후 되읽어 검증할 수 없다. 그래서 HTTP 상태코드가 유일한 성공
  # 근거다. -f 를 쓰지 않고 상태코드를 직접 본다 — 어느 실패든 같은 자리에서 판정하기 위해서다.
  http=$(curl -sS -m "$CURL_MAX_SEC" -o /dev/null -w '%{http_code}' \
    -X PUT -T "$enc" "${PAR_URL}${object}" 2>/dev/null) || http=000
  if [[ ! "$http" =~ ^2[0-9][0-9]$ ]]; then
    warn "업로드가 실패했습니다(HTTP $http). object=$object"
    return 1
  fi

  epoch=$(date '+%s')
  write_metrics 1 "$epoch" "$size"
  log "백업 완료: $object (${size}B, HTTP $http)"
}

main() {
  case "${1:-}" in
    --dry-run) DRY_RUN=true ;;
    --notify-failure)
      load_config || return 1
      notify "🔴 woni DB 백업 유닛이 실패했습니다: ${2:-unknown}" urgent
      return 0
      ;;
    -h | --help)
      usage
      return 0
      ;;
    "") ;;
    *)
      usage >&2
      return 2
      ;;
  esac

  require_command docker || return 1
  require_command age || return 1
  require_command curl || return 1
  load_config || return 1
  load_db_credentials || return 1

  if run_backup; then
    return 0
  fi

  mark_failure
  # 하드 실패는 매일 같은 사유로 반복될 수 있지만 억제하지 않는다 — 백업 실패는 복구 수단이
  # 0 이 되는 사건이고, 조용해지는 쪽의 위험이 소음보다 크다.
  notify "🚨 woni DB 백업에 실패했습니다. journalctl -u woni-backup.service 를 확인하세요." urgent || true
  return 1
}

main "$@"

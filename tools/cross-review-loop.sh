#!/usr/bin/env bash
# Gemini ↔ Claude 교차 리뷰-수정 자동 루프
# ────────────────────────────────────────────────────────────────────────────
# Gemini 가 코딩 → Claude 가 project-code-review 스킬로 리뷰 → Gemini 가 수정,
# 을 "리뷰 통과(APPROVED)" 또는 최대 반복 횟수까지 무인 반복한다.
#
# 사용법:
#   tools/cross-review-loop.sh "<작업 프롬프트>" [최대반복=3]
#   SKIP_INITIAL=1 tools/cross-review-loop.sh "" [최대반복]   # 코딩 단계 생략, 현재 변경분만 리뷰-수정
#
# 권한:
#   - Gemini 수정: --approval-mode auto_edit (파일 편집만 자동, 셸/위험작업 차단)
#   - Claude 리뷰: 읽기 전용(--allowedTools 화이트리스트, Edit/Write 미부여)
#
# 주의:
#   - 커밋은 하지 않는다(절대 규칙: 커밋 전 사용자 확인). 루프는 편집까지만.
#   - Gemini 수정은 .gemini/settings.json 훅(tdd-guard 등) 적용을 받는다.
#   - 두 모델을 반복 호출하므로 시간/비용이 든다. 최대 반복으로 상한을 둔다.

set -uo pipefail

# ── 설정 ─────────────────────────────────────────────────────────────────────
TASK="${1:-}"
MAX_ITER="${2:-3}"
SKIP_INITIAL="${SKIP_INITIAL:-0}"

GEMINI_MODE="--approval-mode auto_edit"
CLAUDE_TOOLS="Read,Bash,Grep,Glob,Skill"   # 읽기 전용 — Edit/Write 의도적으로 제외

PROJECT_ROOT=$(git rev-parse --show-toplevel 2>/dev/null || pwd)
cd "$PROJECT_ROOT" || { echo "프로젝트 루트로 이동 실패" >&2; exit 1; }

log() { printf '\033[1;36m[loop]\033[0m %s\n' "$*" >&2; }
err() { printf '\033[1;31m[loop]\033[0m %s\n' "$*" >&2; }

command -v gemini >/dev/null 2>&1 || { err "gemini CLI 가 없습니다."; exit 1; }
command -v claude >/dev/null 2>&1 || { err "claude CLI 가 없습니다."; exit 1; }

if [ "$SKIP_INITIAL" != "1" ] && [ -z "$TASK" ]; then
  err "작업 프롬프트가 필요합니다.  예: tools/cross-review-loop.sh \"module-member 회원가입 구현\""
  err "(현재 변경분만 리뷰-수정하려면: SKIP_INITIAL=1 tools/cross-review-loop.sh \"\")"
  exit 1
fi

# ── 1. (선택) Gemini 초기 코딩 ────────────────────────────────────────────────
if [ "$SKIP_INITIAL" != "1" ]; then
  log "1) Gemini 코딩 시작 — $TASK"
  gemini $GEMINI_MODE -p "$TASK" || { err "Gemini 코딩 단계 실패"; exit 1; }
else
  log "1) 코딩 단계 생략(SKIP_INITIAL=1) — 현재 변경분을 대상으로 시작"
fi

# ── 리뷰 프롬프트(읽기 전용, project-code-review 스킬 사용 + 기계 판독 헤더 요구) ──
read -r -d '' REVIEW_PROMPT <<'EOF'
현재 작업 트리의 변경분(`git diff HEAD`, 변경이 없으면 `git diff main...HEAD`)을
project-code-review 스킬을 활성화해 그 기준·심각도·보고 형식 그대로 리뷰하라.

출력 첫 줄은 반드시 다음 중 하나의 토큰만 단독으로 적는다(다른 텍스트 금지):
- 규칙 위반·버그가 0건이면:  APPROVED
- 수정이 필요하면:          CHANGES_REQUESTED
둘째 줄부터 스킬의 보고 형식대로 상세 리뷰를 적는다(CHANGES_REQUESTED 인 경우 필수).
코드를 수정하지 말고 진단만 한다.
EOF

# ── 2. 리뷰 ↔ 수정 반복 ──────────────────────────────────────────────────────
i=1
while [ "$i" -le "$MAX_ITER" ]; do
  log "2.$i) Claude 리뷰 (읽기 전용)…"
  REVIEW=$(claude -p "$REVIEW_PROMPT" --allowedTools "$CLAUDE_TOOLS" --output-format text 2>/dev/null)
  if [ -z "$REVIEW" ]; then
    err "리뷰 출력이 비었습니다(인증/네트워크 확인). 중단."; exit 1
  fi

  VERDICT=$(printf '%s\n' "$REVIEW" | awk 'NR==1{print $1; exit}')
  printf '%s\n' "$REVIEW" >&2

  case "$VERDICT" in
    APPROVED)
      log "✅ 리뷰 통과(APPROVED) — ${i}회차에서 수렴. 변경분을 확인 후 직접 커밋하세요."
      exit 0
      ;;
  esac

  if [ "$i" -eq "$MAX_ITER" ]; then
    err "⚠️ 최대 반복($MAX_ITER회) 도달, 미수렴. 위 마지막 리뷰를 사람이 직접 확인하세요."
    exit 2
  fi

  log "3.$i) Gemini 수정 (auto_edit)…"
  gemini $GEMINI_MODE -p "직전 코드 리뷰에서 아래 지적을 받았다. 해당 사항을 수정하고, 필요하면 테스트도 갱신하라. 커밋은 하지 마라.

--- 코드 리뷰 ---
$REVIEW" || { err "Gemini 수정 단계 실패"; exit 1; }

  i=$((i + 1))
done

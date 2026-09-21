# multi_currency_household_ledger 하네스 부트스트랩

AI 하네스 파일은 이 저장소가 아니라 **별도 저장소**(`woniApp_ai_settings`)의 `backend/` 하위에 있고,
`.ai-context/` 에 **일반 클론**으로 둔다. 서브모듈이 **아니다**. 루트의 `CLAUDE.md`·`AGENTS.md`·`.claude`
symlink 가 깨져 있으면 아래로 초기화한다.

```bash
git clone https://github.com/leejungkuk/woniApp_ai_settings.git .ai-context
cd .ai-context
git sparse-checkout init --cone
git sparse-checkout set .github backend shared
cd ..
```

`.ai-context/` 는 `.gitignore` 에 있으므로 이 저장소에 커밋되지 않는다.

하네스 본문 위치:

- `.ai-context/backend/.claude` (← `.claude`)
- `.ai-context/backend/.claude/CLAUDE.md` (← `CLAUDE.md`)
- `.ai-context/backend/AGENTS.md` (← `AGENTS.md`)

sparse-checkout 에 `ios` 가 **없는 것은 의도**다. 이쪽에서 iOS 하네스는 읽기 전용을 넘어 아예 보이지
않는다(교차 수정 = 푸시 충돌·유실). 양쪽 인계는 `shared/handoff/` 로만 한다.

## 왜 서브모듈이 아닌가

2026-09-21 에 걷어냈다. iOS 가 2026-09-20 에 같은 전환을 했고(`woni_app` 커밋 `9d17bf6`),
그쪽 근거 4개를 이 저장소에 하나씩 대본 결과다.

- **포인터가 저절로 안 올라간다.** 하네스 PR 을 머지해도 이 저장소의 `.ai-context` 포인터는 그대로다.
  올리려면 커밋 1줄짜리 PR 을 따로 내야 하고, 실제로 `.ai-context` 를 제목에 단 커밋이 **38개**
  (bump 커밋과 그 머지 합산) 쌓였다. 밀리면 최대 50커밋까지 벌어졌다. 로컬에서는 symlink 가
  **작업 트리**를 가리켜 정상 동작하므로 증상이 보이지 않는다.
- **이 저장소는 public 인데 하네스는 private 이다.** `.gitmodules` 가 private URL 을 public 리포에
  노출했고, 제3자는 `submodule update --init` 이 인증 실패해 애초에 받을 수 없었다.
- **어느 워크플로도 서브모듈을 체크아웃하지 않는다.** 제품 CI 7개 전부. 포인터는 CI 에 아무 영향이 없다.

iOS 의 네 번째 근거(`enforce_admins` + 필수 체크 때문에 포인터 PR 마다 풀빌드)는 **이 저장소엔 해당하지
않는다** — `main` 에 브랜치 보호가 없다. 그래서 포인터 PR 이 무겁지는 않았고, 비용은 위의 38커밋이다.

용량 때문이 아니다 — 서브모듈 제거로 줄어드는 추적 용량은 127바이트다. **드리프트를 없애는 게 목적이다.**

## 대신 생긴 것 — worktree 가 하네스를 공유한다

이 저장소는 iOS 와 달리 `execute.py` 가 `.worktrees/<plan>` 에서 러너를 돌린다. 서브모듈이던 때는
worktree 마다 **포인터에 핀 고정된 자체 체크아웃**이 생겼다. 일반 클론은 추적되지 않아 worktree 에
딸려오지 않으므로, `ensure_worktree` 가 메인 체크아웃의 `.ai-context` 를 **symlink 로 잇는다**.

결과: 메인과 모든 worktree 가 하네스 **한 벌**을 공유한다. 러너가 도는 중에 하네스를 고치면 그 러너가
반쯤 고쳐진 훅·스크립트를 읽는다. 하네스 수정은 러너가 놀 때 한다(`CLAUDE.md` 워크플로 5).

## 하네스를 고칠 때

`.ai-context` 는 독립 저장소다. 거기서 브랜치를 따고 커밋·PR 한다. **이 저장소에는 아무 기록도 남지
않는다** — 포인터를 올릴 일이 없다는 뜻이고, 그게 전환의 목적이다.

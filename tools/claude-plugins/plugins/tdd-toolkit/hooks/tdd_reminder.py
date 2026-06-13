#!/usr/bin/env python3
"""TDD reminder hook (PostToolUse, 비차단).

프로덕션 Java 클래스(.../src/main/java/.../Foo.java)를 편집했는데 대응하는
테스트(.../src/test/java/.../FooTest.java)가 없으면, write-unit-test 스킬로
테스트를 작성하도록 부드럽게 알린다. 절대 작업을 차단하지 않는다(항상 exit 0).

stdin 으로 PostToolUse 페이로드(JSON)를 받는다:
  { "tool_name": "...", "tool_input": {"file_path": "..."}, "cwd": "..." }
"""

import json
import os
import sys

# 테스트가 굳이 필요 없는 파일은 알림에서 제외한다(오탐 최소화).
# 필요에 맞게 자유롭게 늘리거나 줄여도 된다.
EXCLUDED_SUFFIXES = (
    "Application.java",   # Spring Boot 부트 클래스
    "package-info.java",
    "module-info.java",
)


def main() -> None:
    raw = sys.stdin.read()
    if not raw.strip():
        return
    payload = json.loads(raw)

    if payload.get("tool_name") not in ("Edit", "Write", "MultiEdit"):
        return

    tool_input = payload.get("tool_input") or {}
    file_path = tool_input.get("file_path")
    if not file_path:
        return

    # 상대 경로면 cwd 기준으로 절대화
    if not os.path.isabs(file_path):
        file_path = os.path.join(payload.get("cwd") or os.getcwd(), file_path)
    file_path = os.path.normpath(file_path)

    # 프로덕션 Java 소스만 대상
    if not file_path.endswith(".java"):
        return
    if "/src/main/java/" not in file_path.replace(os.sep, "/"):
        return
    if file_path.endswith(EXCLUDED_SUFFIXES):
        return

    # 대응 테스트 경로 계산: src/main/java → src/test/java, Foo.java → FooTest.java
    test_path = file_path.replace("/src/main/java/", "/src/test/java/")
    test_path = test_path[: -len(".java")] + "Test.java"

    if os.path.exists(test_path):
        return  # 이미 테스트가 있으면 조용히 통과

    rel = os.path.basename(file_path)
    test_rel = os.path.basename(test_path)
    message = (
        f"⚠️ TDD: {rel} 를 편집했지만 대응 테스트({test_rel})가 없습니다. "
        f"write-unit-test 스킬로 테스트를 작성하는 것을 권장합니다."
    )

    output = {
        "systemMessage": message,
        "hookSpecificOutput": {
            "hookEventName": "PostToolUse",
            "additionalContext": (
                f"프로덕션 클래스 {rel} 에 대응하는 테스트 파일 {test_rel} 가 "
                f"존재하지 않습니다(예상 경로: {test_path}). 이 변경에 테스트가 필요한지 "
                f"판단하고, 필요하면 write-unit-test 스킬을 사용해 프로젝트 컨벤션에 맞는 "
                f"테스트를 작성하세요. 설정/단순 위임 클래스 등 테스트가 불필요하다고 "
                f"판단되면 무시해도 됩니다."
            ),
        },
    }
    print(json.dumps(output, ensure_ascii=False))


if __name__ == "__main__":
    try:
        main()
    except Exception:
        # 어떤 경우에도 세션을 방해하지 않는다.
        pass
    sys.exit(0)

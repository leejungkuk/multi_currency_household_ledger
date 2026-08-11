#!/usr/bin/env python3
"""monitoring/ 기준선 이탈을 차단하고 이미지 태그 범프만 허용한다."""

from __future__ import annotations

import argparse
import copy
import hashlib
import json
import os
import re
import sys
from dataclasses import dataclass
from pathlib import Path
import subprocess
import tempfile
from typing import Any, Callable

import yaml


ROOT = Path(__file__).resolve().parents[1]
MONITORING_DIR = ROOT / "monitoring"
BASELINE_PATH = ROOT / "tools" / "monitoring-baseline.json"
COMPOSE_PATHS = (
    "monitoring/docker-compose.yml",
    "monitoring/docker-compose.deploy.yml",
)
# monitoring-sync.sh의 rsync --exclude='.env'로 호스트에 배달되지 않아 검사 대상에서도 제외한다.
IGNORED_RUNTIME_FILES = {"monitoring/.env"}
MISSING = object()


class GuardError(RuntimeError):
    """검사가 실행되지 못한 상태를 정상 통과와 구분한다."""


@dataclass
class Snapshot:
    files: dict[str, str]
    compose: dict[str, Any]
    services: list[str]
    compose_contents: dict[str, bytes] | None = None


@dataclass
class MemoryState:
    files: dict[str, str]
    raw_compose: dict[str, Any]
    services: list[str]
    compose_contents: dict[str, bytes]


@dataclass
class Evaluation:
    passed: bool
    violations: list[str]
    tag_only_files: list[str]
    tag_transitions: list[str]


def sha256_bytes(content: bytes) -> str:
    return hashlib.sha256(content).hexdigest()


def sha256_path(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as file:
        for chunk in iter(lambda: file.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def collect_file_hashes() -> dict[str, str]:
    if MONITORING_DIR.is_symlink():
        raise GuardError("monitoring/ 심링크는 허용하지 않는다")
    if not MONITORING_DIR.is_dir():
        raise GuardError("monitoring/ 디렉터리가 없다")

    hashes: dict[str, str] = {}
    try:
        paths = sorted(MONITORING_DIR.rglob("*"), key=lambda path: path.as_posix())
        for path in paths:
            relative = path.relative_to(ROOT).as_posix()
            if path.is_symlink():
                raise GuardError(f"monitoring/ 심링크는 허용하지 않는다: {relative}")
            if relative in IGNORED_RUNTIME_FILES:
                continue
            if path.is_file():
                hashes[relative] = sha256_path(path)
    except OSError as error:
        raise GuardError(f"monitoring/ 파일 해시 계산 실패: {error}") from error

    if not hashes:
        raise GuardError("monitoring/ 파일이 0개다")
    return hashes


def load_raw_compose() -> dict[str, Any]:
    loaded: dict[str, Any] = {}
    for relative in COMPOSE_PATHS:
        path = ROOT / relative
        try:
            with path.open(encoding="utf-8") as file:
                document = yaml.safe_load(file)
        except (OSError, yaml.YAMLError) as error:
            raise GuardError(f"{relative} safe_load 실패: {error}") from error
        if not isinstance(document, dict):
            raise GuardError(f"{relative} 최상위 구조가 mapping이 아니다")
        loaded[relative] = document
    return loaded


def load_compose_contents() -> dict[str, bytes]:
    try:
        return {relative: (ROOT / relative).read_bytes() for relative in COMPOSE_PATHS}
    except OSError as error:
        raise GuardError(f"compose 원문 로드 실패: {error}") from error


def compose_services() -> list[str]:
    environment = os.environ.copy()
    # config 해석에만 쓰는 비시크릿 값이다. CI에는 monitoring/.env가 없어도 검사가 돌아야 한다.
    if not environment.get("GRAFANA_ADMIN_PASSWORD"):
        environment["GRAFANA_ADMIN_PASSWORD"] = "monitoring-guard-config-only"
    try:
        result = subprocess.run(
            [
                "docker",
                "compose",
                "-f",
                COMPOSE_PATHS[0],
                "config",
                "--services",
            ],
            cwd=ROOT,
            env=environment,
            capture_output=True,
            text=True,
            timeout=60,
            check=False,
        )
    except (OSError, subprocess.TimeoutExpired) as error:
        raise GuardError(f"docker compose config --services 실행 실패: {error}") from error
    if result.returncode != 0:
        detail = " ".join((result.stderr or result.stdout).split())
        raise GuardError(
            f"docker compose config --services rc={result.returncode}: {detail or '출력 없음'}"
        )
    services = sorted(line.strip() for line in result.stdout.splitlines() if line.strip())
    if not services:
        raise GuardError("docker compose config --services 결과가 비었다")
    return services


def collect_memory_state() -> MemoryState:
    return MemoryState(
        files=collect_file_hashes(),
        raw_compose=load_raw_compose(),
        services=compose_services(),
        compose_contents=load_compose_contents(),
    )


def snapshot_from_state(state: MemoryState) -> Snapshot:
    return Snapshot(
        files=copy.deepcopy(state.files),
        compose=copy.deepcopy(state.raw_compose),
        services=copy.deepcopy(state.services),
        compose_contents=copy.deepcopy(state.compose_contents),
    )


def load_baseline() -> Snapshot:
    try:
        with BASELINE_PATH.open(encoding="utf-8") as file:
            document = json.load(file)
    except (OSError, json.JSONDecodeError) as error:
        raise GuardError(f"기준선 {BASELINE_PATH.relative_to(ROOT)} 로드 실패: {error}") from error

    if not isinstance(document, dict) or set(document) != {"files", "compose", "services"}:
        raise GuardError("기준선 최상위 키는 files, compose, services와 완전 일치해야 한다")
    files = document["files"]
    compose = document["compose"]
    services = document["services"]
    if not isinstance(files, dict) or not all(
        isinstance(path, str) and isinstance(digest, str) for path, digest in files.items()
    ):
        raise GuardError("기준선 files 형식이 올바르지 않다")
    if not isinstance(compose, dict) or set(compose) != set(COMPOSE_PATHS):
        raise GuardError("기준선 compose 파일 집합이 올바르지 않다")
    if not isinstance(services, list) or not all(isinstance(item, str) for item in services):
        raise GuardError("기준선 services 형식이 올바르지 않다")
    return Snapshot(files=files, compose=compose, services=services)


def json_value(value: Any) -> str:
    if value is MISSING:
        return "<없음>"
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def child_path(path: str, key: Any) -> str:
    if isinstance(key, int):
        return f"{path}[{key}]"
    if isinstance(key, str) and key.replace("_", "").replace("-", "").isalnum():
        return f"{path}.{key}" if path else key
    return f"{path}[{json.dumps(key, ensure_ascii=False)}]"


def value_differences(
    path: tuple[Any, ...], expected: Any, current: Any
) -> list[tuple[tuple[Any, ...], Any, Any]]:
    if isinstance(expected, dict) and isinstance(current, dict):
        differences: list[tuple[tuple[Any, ...], Any, Any]] = []
        for key in sorted(set(expected) | set(current), key=str):
            differences.extend(
                value_differences(
                    (*path, key),
                    expected.get(key, MISSING),
                    current.get(key, MISSING),
                )
            )
        return differences
    if isinstance(expected, list) and isinstance(current, list):
        differences = []
        for index in range(max(len(expected), len(current))):
            differences.extend(
                value_differences(
                    (*path, index),
                    expected[index] if index < len(expected) else MISSING,
                    current[index] if index < len(current) else MISSING,
                )
            )
        return differences
    if type(expected) is not type(current) or expected != current:
        return [(path, expected, current)]
    return []


def path_text(path: tuple[Any, ...]) -> str:
    result = ""
    for key in path:
        result = child_path(result, key)
    return result


def split_image_tag(image: str) -> tuple[str, str] | None:
    if "@" in image:
        return None
    last_slash = image.rfind("/")
    last_colon = image.rfind(":")
    if last_colon <= last_slash or not image[:last_colon] or not image[last_colon + 1 :]:
        return None
    return image[:last_colon], image[last_colon + 1 :]


def compose_image_tag_transitions(
    baseline: Snapshot, current: Snapshot, changed_files: list[str]
) -> list[str] | None:
    changes = value_differences((), baseline.compose, current.compose)
    replacements: dict[str, list[tuple[bytes, bytes]]] = {}
    transitions: list[str] = []
    for path, expected, actual in changes:
        # image 경로 외 차이를 빠르게 거르지만 실제 차단 보증은 후단 바이트 재구성이 맡는다.
        # 정합 fixture는 후단도 차단하고 비정합 fixture는 self-test 단언에 걸리므로,
        # 이 분기만 격리하는 self-test 케이스는 만들 수 없다.
        if (
            len(path) != 4
            or path[0] not in COMPOSE_PATHS
            or path[1] != "services"
            or not isinstance(path[2], str)
            or path[3] != "image"
            or not isinstance(expected, str)
            or not isinstance(actual, str)
        ):
            return None
        expected_tag = split_image_tag(expected)
        actual_tag = split_image_tag(actual)
        if expected_tag is None or actual_tag is None or expected_tag[0] != actual_tag[0]:
            return None
        if any(
            re.fullmatch(r"[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}", tag) is None
            for tag in (expected_tag[1], actual_tag[1])
        ):
            return None
        replacements.setdefault(path[0], []).append((actual.encode(), expected.encode()))
        transitions.append(
            f"{path[0]} {path[2]}: {expected_tag[1]} → {actual_tag[1]}"
        )

    if not changes or set(changed_files) != set(replacements):
        return None
    if current.compose_contents is None:
        return None
    for path, path_replacements in replacements.items():
        content = current.compose_contents.get(path)
        if content is None or sha256_bytes(content) != current.files.get(path):
            return None
        candidates = {content}
        for actual, expected in path_replacements:
            next_candidates: set[bytes] = set()
            for candidate in candidates:
                start = 0
                while (index := candidate.find(actual, start)) >= 0:
                    replacement = (
                        candidate[:index] + expected + candidate[index + len(actual) :]
                    )
                    if replacement not in next_candidates and len(next_candidates) >= 1_000:
                        return None
                    next_candidates.add(replacement)
                    start = index + 1
            candidates = next_candidates
        if not any(sha256_bytes(candidate) == baseline.files.get(path) for candidate in candidates):
            return None
    return transitions


def compose_invariant_differences(snapshot: Snapshot) -> list[str]:
    base_services = snapshot.compose[COMPOSE_PATHS[0]].get("services")
    override_services = snapshot.compose[COMPOSE_PATHS[1]].get("services")
    if override_services is None:
        return []
    if not isinstance(base_services, dict) or not isinstance(override_services, dict):
        return ["compose override 서비스 불변식 — services는 mapping이어야 한다"]
    override_only = sorted(set(override_services) - set(base_services))
    if override_only:
        return [
            "compose override services ⊆ base — base에 없는 서비스 "
            + json_value(override_only)
        ]
    return []


def evaluate(baseline: Snapshot, current: Snapshot) -> Evaluation:
    file_differences: list[str] = []
    changed_files: list[str] = []
    for path in sorted(set(baseline.files) | set(current.files)):
        expected = baseline.files.get(path, MISSING)
        actual = current.files.get(path, MISSING)
        if expected != actual:
            changed_files.append(path)
            file_differences.append(
                f"{path} — {json_value(expected)} → {json_value(actual)}"
            )

    compose_differences = [
        f"{path_text(path)} — {json_value(expected)} → {json_value(actual)}"
        for path, expected, actual in value_differences(
            ("compose",), baseline.compose, current.compose
        )
    ]
    service_differences = [
        f"{path_text(path)} — {json_value(expected)} → {json_value(actual)}"
        for path, expected, actual in value_differences(
            ("services",), baseline.services, current.services
        )
    ]
    invariant_differences = compose_invariant_differences(current)
    tag_transitions = compose_image_tag_transitions(baseline, current, changed_files)
    tag_only = (
        bool(changed_files)
        and set(changed_files).issubset(COMPOSE_PATHS)
        and tag_transitions is not None
        and not service_differences
        and not invariant_differences
    )
    if tag_only:
        return Evaluation(
            passed=True,
            violations=[],
            tag_only_files=changed_files,
            tag_transitions=tag_transitions,
        )

    violations = (
        file_differences
        + compose_differences
        + service_differences
        + invariant_differences
    )
    return Evaluation(
        passed=not violations,
        violations=violations,
        tag_only_files=[],
        tag_transitions=[],
    )


def print_evaluation(evaluation: Evaluation) -> None:
    if evaluation.passed:
        if evaluation.tag_only_files:
            joined = ", ".join(evaluation.tag_only_files)
            transitions = ", ".join(evaluation.tag_transitions)
            print(f"허용된 compose image 태그 변경: {joined} | {transitions}")
        print("monitoring-guard: 위반 0건")
        return

    for violation in evaluation.violations:
        print(violation)
    print(f"monitoring-guard: 위반 {len(evaluation.violations)}건")
    print("정당한 변경이면 --update-baseline으로 갱신하고 그 diff를 리뷰받으라.")


def write_baseline(snapshot: Snapshot) -> None:
    invariant_differences = compose_invariant_differences(snapshot)
    if invariant_differences:
        raise GuardError("baseline 갱신 거부: " + "; ".join(invariant_differences))
    document = {
        "files": snapshot.files,
        "compose": snapshot.compose,
        "services": snapshot.services,
    }
    temporary_path: Path | None = None
    try:
        content = (
            json.dumps(
                document,
                ensure_ascii=False,
                indent=2,
                sort_keys=True,
                allow_nan=False,
            )
            + "\n"
        )
        with tempfile.NamedTemporaryFile(
            mode="w",
            encoding="utf-8",
            dir=BASELINE_PATH.parent,
            prefix=".monitoring-baseline.",
            delete=False,
        ) as file:
            temporary_path = Path(file.name)
            file.write(content)
        temporary_path.chmod(0o644)
        os.replace(temporary_path, BASELINE_PATH)
    except (OSError, TypeError, ValueError) as error:
        if temporary_path is not None:
            temporary_path.unlink(missing_ok=True)
        raise GuardError(f"기준선 저장 실패: {error}") from error
    print(
        f"{BASELINE_PATH.relative_to(ROOT)} 갱신: "
        f"파일 {len(snapshot.files)}개, 서비스 {len(snapshot.services)}개"
    )


def changed_digest(digest: str) -> str:
    candidate = "0" * 64
    return "1" * 64 if digest == candidate else candidate


def mark_compose_changed(state: MemoryState, path: str) -> None:
    state.files[path] = changed_digest(state.files[path])


def replace_image_tag(image: str, tag: str) -> str:
    last_slash = image.rfind("/")
    last_colon = image.rfind(":")
    if last_colon <= last_slash:
        raise GuardError(f"self-test 이미지에 태그가 없다: {image}")
    return f"{image[:last_colon]}:{tag}"


def change_image(state: MemoryState, path: str, service: str, changed: str) -> None:
    service_config = state.raw_compose[path]["services"][service]
    original = service_config["image"]
    original_bytes = original.encode()
    content = state.compose_contents[path]
    if original_bytes not in content:
        raise GuardError(f"self-test image 원문을 찾을 수 없다: {original}")
    changed_content = content.replace(original_bytes, changed.encode(), 1)
    service_config["image"] = changed
    state.compose_contents[path] = changed_content
    state.files[path] = sha256_bytes(changed_content)


def change_image_tag(state: MemoryState, path: str, service: str, tag: str) -> None:
    original = state.raw_compose[path]["services"][service]["image"]
    change_image(state, path, service, replace_image_tag(original, tag))


def mutate_file_in_memory(
    state: MemoryState, path: str, transform: Callable[[bytes], bytes]
) -> None:
    original = (ROOT / path).read_bytes()
    changed = transform(original)
    if changed == original:
        raise GuardError(f"self-test 변조가 파일을 바꾸지 않았다: {path}")
    state.files[path] = sha256_bytes(changed)


def apply_self_test_mutation(case: int, state: MemoryState) -> None:
    base = state.raw_compose[COMPOSE_PATHS[0]]
    deploy = state.raw_compose[COMPOSE_PATHS[1]]
    services = base["services"]

    if case == 1:
        base["include"] = "./evil.yml"
        state.services.append("evil")
        state.services.sort()
    elif case == 2:
        base["volumes"]["prometheus-data"]["driver_opts"] = {
            "type": "none",
            "device": "/",
            "o": "bind",
        }
    elif case == 3:
        services["prometheus"]["privileged"] = True
    elif case == 4:
        services["prometheus"]["ports"][0] = "0.0.0.0:9090:9090"
    elif case == 5:
        services["prometheus"]["volumes"].append("../deploy/.env:/x:ro")
    elif case == 6:
        services["node-exporter"]["volumes"][0] = "/:/host"
    elif case == 7:
        services["grafana"]["environment"]["SELF_TEST_SECRET"] = "${SUPABASE_DB_PASSWORD}"
    elif case == 8:
        services["grafana"]["environment"]["SELF_TEST_SECRET"] = "$SUPABASE_DB_PASSWORD"
    elif case == 9:
        services["grafana"]["environment"]["GF_PLUGINS_PREINSTALL"] = (
            "evil-app@https://example.invalid/evil.zip"
        )
    elif case == 10:
        services["docker-socket-proxy"]["environment"]["POST"] = 1
    elif case == 11:
        base["x-logging"]["driver"] = "syslog"
        for service in services.values():
            service["logging"]["driver"] = "syslog"
    elif case == 12:
        services["prometheus"]["extra_hosts"].append("loki:203.0.113.5")
    elif case == 13:
        change_image(
            state,
            COMPOSE_PATHS[0],
            "prometheus",
            "attacker/prometheus:v3.13.2",
        )
        return
    elif case == 14:
        change_image_tag(state, COMPOSE_PATHS[0], "prometheus", "self-test-tag")
        return
    elif case == 15:
        services["prometheus"]["command"].append("--web.enable-admin-api")
    elif case == 16:
        deploy["services"]["deploy-only"] = {"image": "busybox:1.37"}
    elif case == 17:
        mutate_file_in_memory(
            state,
            "monitoring/loki/loki-config.yml",
            lambda content: content.replace(b"object_store: filesystem", b"object_store: s3", 1),
        )
        return
    elif case == 18:
        state.files["monitoring/grafana/provisioning/datasources/evil.yml"] = sha256_bytes(
            b"apiVersion: 1\n"
        )
        return
    elif case == 19:
        state.files["monitoring/prometheus/targets/evil.json"] = sha256_bytes(b"[]\n")
        return
    elif case == 20:
        mutate_file_in_memory(
            state,
            "monitoring/alloy/config.alloy",
            lambda content: content + b"\n",
        )
        return
    elif case == 21:
        change_image_tag(
            state, COMPOSE_PATHS[0], "prometheus", "self-test-mixed"
        )
        services["prometheus"]["privileged"] = True
        path = COMPOSE_PATHS[0]
        marker = b"    image: prom/prometheus:self-test-mixed\n"
        content = state.compose_contents[path].replace(
            marker, marker + b"    privileged: true\n", 1
        )
        if content == state.compose_contents[path]:
            raise GuardError("self-test privileged 원문 삽입 위치를 찾을 수 없다")
        state.compose_contents[path] = content
        state.files[path] = sha256_bytes(content)
        return
    elif case == 22:
        content = state.compose_contents[COMPOSE_PATHS[0]] + b"\n"
        state.compose_contents[COMPOSE_PATHS[0]] = content
        state.files[COMPOSE_PATHS[0]] = sha256_bytes(content)
        return
    elif case == 23:
        deploy["services"]["prometheus"]["volumes"].append("/:/host")
    elif case == 24:
        change_image_tag(
            state,
            COMPOSE_PATHS[0],
            "prometheus",
            "${SUPABASE_DB_PASSWORD}",
        )
        return
    elif case == 25:
        change_image_tag(
            state,
            COMPOSE_PATHS[0],
            "prometheus",
            "$SUPABASE_DB_PASSWORD",
        )
        return
    elif case == 26:
        change_image_tag(
            state, COMPOSE_PATHS[0], "prometheus", "self-test-file-axis"
        )
        path = COMPOSE_PATHS[1]
        content = state.compose_contents[path] + b"\n"
        state.compose_contents[path] = content
        state.files[path] = sha256_bytes(content)
        return
    elif case == 27:
        change_image_tag(
            state, COMPOSE_PATHS[0], "prometheus", "self-test-service-axis"
        )
        state.services.append("service-list-with-tag-bump")
        state.services.sort()
        return
    else:
        raise GuardError(f"알 수 없는 self-test 케이스: {case}")

    mark_compose_changed(
        state,
        COMPOSE_PATHS[1] if case in (16, 23) else COMPOSE_PATHS[0],
    )


SELF_TEST_NAMES = [
    "최상위 include 추가",
    "최상위 volume driver_opts 추가",
    "privileged 활성화",
    "포트를 0.0.0.0에 공개",
    "상대경로 시크릿 마운트 추가",
    "루트 마운트 ro 제거",
    "${VAR} 보간 유출",
    "$VAR 보간 유출",
    "Grafana 외부 플러그인 값 추가",
    "socket-proxy POST 활성화",
    "x-logging syslog 변경",
    "기존 extra_hosts 값 추가",
    "image 리포지터리 변경",
    "image 태그만 변경",
    "Prometheus 관리 API 명령 추가",
    "deploy override 전용 서비스 추가",
    "Loki object_store를 s3로 변경",
    "Grafana datasource 파일 추가",
    "Prometheus target 파일 추가",
    "기존 설정 파일 1바이트 변경",
    "image 태그 범프와 다른 변경 혼합",
    "태그 아닌 compose 바이트만 변경",
    "deploy override 기존 서비스 루트 rw 마운트 추가",
    "image 태그를 ${SUPABASE_DB_PASSWORD}로 변경",
    "image 태그를 $SUPABASE_DB_PASSWORD로 변경",
    "정상 태그 범프와 다른 compose 원문 변경 혼합",
    "정상 태그 범프와 서비스 목록 변경 혼합",
]


def require_self_test_start(evaluation: Evaluation) -> None:
    if not evaluation.passed:
        raise GuardError("self-test 시작 전 현재 트리가 기준선과 일치하지 않는다")


def run_mutation_cases(
    baseline: Snapshot, source_state: MemoryState, source_name: str
) -> tuple[int, int]:
    passed = 0
    for case, name in enumerate(SELF_TEST_NAMES, start=1):
        state = copy.deepcopy(source_state)
        apply_self_test_mutation(case, state)
        inconsistent_paths = []
        for path in COMPOSE_PATHS:
            if state.compose_contents[path] == source_state.compose_contents[path]:
                continue
            try:
                loaded = yaml.safe_load(state.compose_contents[path])
            except yaml.YAMLError:
                loaded = MISSING
            if (
                loaded != state.raw_compose[path]
                or state.files[path] != sha256_bytes(state.compose_contents[path])
            ):
                inconsistent_paths.append(path)
        if inconsistent_paths:
            print(
                f"FAIL {source_name} {case:02d} | {name} | "
                f"fixture 원문·구조 불일치: {', '.join(inconsistent_paths)}"
            )
            continue
        current = snapshot_from_state(state)
        case_baseline = current if case == 16 else baseline
        evaluation = evaluate(case_baseline, current)
        expected_pass = case == 14
        override_structure_detected = case != 23 or any(
            COMPOSE_PATHS[1] in violation
            and ".services.prometheus.volumes" in violation
            for violation in evaluation.violations
        )
        if evaluation.passed == expected_pass and override_structure_detected:
            outcome = "허용" if expected_pass else "차단"
            print(f"PASS {source_name} {case:02d} | {name} | {outcome}")
            passed += 1
        else:
            outcome = "통과(오류)" if evaluation.passed else "차단(오류)"
            print(f"FAIL {source_name} {case:02d} | {name} | {outcome}")
            for violation in evaluation.violations[:3]:
                print(f"  {violation}")
    return passed, len(SELF_TEST_NAMES)


def run_self_test() -> int:
    global ROOT, MONITORING_DIR, BASELINE_PATH

    baseline = load_baseline()
    pristine_state = collect_memory_state()
    pristine = evaluate(baseline, snapshot_from_state(pristine_state))
    require_self_test_start(pristine)

    passed, total = run_mutation_cases(baseline, pristine_state, "기준선")

    tag_state = copy.deepcopy(pristine_state)
    change_image_tag(tag_state, COMPOSE_PATHS[0], "prometheus", "self-test-workflow")
    tag_evaluation = evaluate(baseline, snapshot_from_state(tag_state))
    try:
        require_self_test_start(tag_evaluation)
        workflow_passed = tag_evaluation.passed and bool(tag_evaluation.tag_only_files)
    except GuardError:
        workflow_passed = False
    total += 1
    if workflow_passed:
        print("PASS 회귀 | 태그 범프 트리에서 기본 검사와 self-test 사전 게이트 | 허용")
        passed += 1
        case_passed, case_total = run_mutation_cases(baseline, tag_state, "태그범프")
        passed += case_passed
        total += case_total
    else:
        print("FAIL 회귀 | 태그 범프 트리에서 기본 검사와 self-test 사전 게이트 | 차단(오류)")

    expected_transition = [
        "monitoring/docker-compose.yml prometheus: v3.13.2 → self-test-workflow"
    ]
    total += 1
    if tag_evaluation.tag_transitions == expected_transition:
        print("PASS 회귀 | 태그 범프 전이 내용 | " + expected_transition[0])
        passed += 1
    else:
        print("FAIL 회귀 | 태그 범프 전이 내용 | 누락/불일치(오류)")

    digest_baseline_state = copy.deepcopy(pristine_state)
    change_image(
        digest_baseline_state,
        COMPOSE_PATHS[0],
        "prometheus",
        f"prom/prometheus@sha256:{'a' * 64}",
    )
    digest_current_state = copy.deepcopy(digest_baseline_state)
    change_image(
        digest_current_state,
        COMPOSE_PATHS[0],
        "prometheus",
        f"prom/prometheus@sha256:{'b' * 64}",
    )
    digest_evaluation = evaluate(
        snapshot_from_state(digest_baseline_state),
        snapshot_from_state(digest_current_state),
    )
    total += 1
    if not digest_evaluation.passed:
        print("PASS 회귀 | image digest 교체 | 차단")
        passed += 1
    else:
        print("FAIL 회귀 | image digest 교체 | 통과(오류)")

    pathological_state = copy.deepcopy(pristine_state)
    pathological_services = pathological_state.raw_compose[COMPOSE_PATHS[0]][
        "services"
    ]
    for index, service in enumerate(pathological_services):
        change_image_tag(
            pathological_state,
            COMPOSE_PATHS[0],
            service,
            f"self-test-pathological-{index}",
        )
    repeated_images = b"\n".join(
        f"# {config['image']}".encode()
        for config in pathological_services.values()
        for _ in range(19)
    )
    content = pathological_state.compose_contents[COMPOSE_PATHS[0]] + repeated_images
    pathological_state.compose_contents[COMPOSE_PATHS[0]] = content
    pathological_state.files[COMPOSE_PATHS[0]] = sha256_bytes(content)
    pathological_evaluation = evaluate(
        baseline, snapshot_from_state(pathological_state)
    )
    total += 1
    if not pathological_evaluation.passed:
        print("PASS 회귀 | image 6종×20회 원문 반복 | 후보 상한으로 차단")
        passed += 1
    else:
        print("FAIL 회귀 | image 6종×20회 원문 반복 | 통과(오류)")

    service_state = copy.deepcopy(pristine_state)
    service_state.services.append("service-list-only")
    service_state.services.sort()
    service_evaluation = evaluate(baseline, snapshot_from_state(service_state))
    total += 1
    if not service_evaluation.passed:
        print("PASS 회귀 | 구조 diff 없는 서비스 목록 변경 | 차단")
        passed += 1
    else:
        print("FAIL 회귀 | 구조 diff 없는 서비스 목록 변경 | 통과(오류)")

    original_root = ROOT
    original_monitoring_dir = MONITORING_DIR
    try:
        with tempfile.TemporaryDirectory(prefix="monitoring-guard-self-test-") as directory:
            ROOT = Path(directory)
            MONITORING_DIR = ROOT / "monitoring"
            MONITORING_DIR.mkdir()
            (MONITORING_DIR / "existing.yml").write_text("existing\n", encoding="utf-8")
            before_files = collect_file_hashes()
            (MONITORING_DIR / "injected.yml").write_text("injected\n", encoding="utf-8")
            after_files = collect_file_hashes()
            empty_compose = {path: {"services": {}} for path in COMPOSE_PATHS}
            file_walk_passed = not evaluate(
                Snapshot(files=before_files, compose=empty_compose, services=[]),
                Snapshot(files=after_files, compose=empty_compose, services=[]),
            ).passed
    except GuardError:
        file_walk_passed = False
    finally:
        ROOT = original_root
        MONITORING_DIR = original_monitoring_dir
    total += 1
    if file_walk_passed:
        print("PASS 회귀 | 임시 트리 신규 파일 실제 워크 | 차단")
        passed += 1
    else:
        print("FAIL 회귀 | 임시 트리 신규 파일 실제 워크 | 통과(오류)")

    original_root = ROOT
    original_monitoring_dir = MONITORING_DIR
    symlink_passed = False
    try:
        with tempfile.TemporaryDirectory(prefix="monitoring-guard-symlink-test-") as directory:
            ROOT = Path(directory)
            MONITORING_DIR = ROOT / "monitoring"
            MONITORING_DIR.mkdir()
            outside = ROOT / "outside-compose.yml"
            outside.write_text("services: {}\n", encoding="utf-8")
            (MONITORING_DIR / "docker-compose.yml").symlink_to("../outside-compose.yml")
            try:
                collect_file_hashes()
            except GuardError as error:
                symlink_passed = "심링크" in str(error)
    finally:
        ROOT = original_root
        MONITORING_DIR = original_monitoring_dir
    total += 1
    if symlink_passed:
        print("PASS 회귀 | compose를 트리 밖 파일 심링크로 치환 | 차단")
        passed += 1
    else:
        print("FAIL 회귀 | compose를 트리 밖 파일 심링크로 치환 | 통과(오류)")

    original_root = ROOT
    original_monitoring_dir = MONITORING_DIR
    root_symlink_passed = False
    try:
        with tempfile.TemporaryDirectory(prefix="monitoring-guard-root-symlink-test-") as directory:
            ROOT = Path(directory)
            outside = ROOT / "outside-monitoring"
            outside.mkdir()
            (outside / "config.yml").write_text("safe\n", encoding="utf-8")
            MONITORING_DIR = ROOT / "monitoring"
            MONITORING_DIR.symlink_to(outside, target_is_directory=True)
            try:
                collect_file_hashes()
            except GuardError as error:
                root_symlink_passed = "심링크" in str(error)
    finally:
        ROOT = original_root
        MONITORING_DIR = original_monitoring_dir
    total += 1
    if root_symlink_passed:
        print("PASS 회귀 | monitoring 루트 디렉터리 심링크 | 차단")
        passed += 1
    else:
        print("FAIL 회귀 | monitoring 루트 디렉터리 심링크 | 통과(오류)")

    original_root = ROOT
    original_monitoring_dir = MONITORING_DIR
    directory_symlink_passed = False
    try:
        with tempfile.TemporaryDirectory(prefix="monitoring-guard-dir-symlink-test-") as directory:
            ROOT = Path(directory)
            MONITORING_DIR = ROOT / "monitoring"
            MONITORING_DIR.mkdir()
            outside = ROOT / "outside-config"
            outside.mkdir()
            (outside / "config.yml").write_text("safe\n", encoding="utf-8")
            (MONITORING_DIR / "linked-config").symlink_to(
                outside, target_is_directory=True
            )
            try:
                collect_file_hashes()
            except GuardError as error:
                directory_symlink_passed = "심링크" in str(error)
    finally:
        ROOT = original_root
        MONITORING_DIR = original_monitoring_dir
    total += 1
    if directory_symlink_passed:
        print("PASS 회귀 | monitoring 중첩 디렉터리 심링크 | 차단")
        passed += 1
    else:
        print("FAIL 회귀 | monitoring 중첩 디렉터리 심링크 | 통과(오류)")

    original_run = subprocess.run
    compose_nonzero_passed = False
    try:
        subprocess.run = lambda *args, **kwargs: subprocess.CompletedProcess(
            args=args[0], returncode=23, stdout="prometheus\ngrafana\n", stderr=""
        )
        try:
            compose_services()
        except GuardError as error:
            compose_nonzero_passed = "rc=23" in str(error)
    finally:
        subprocess.run = original_run
    total += 1
    if compose_nonzero_passed:
        print("PASS 회귀 | compose non-zero와 그럴듯한 stdout 동시 반환 | 차단")
        passed += 1
    else:
        print("FAIL 회귀 | compose non-zero와 그럴듯한 stdout 동시 반환 | 통과(오류)")

    original_baseline_path = BASELINE_PATH
    serialization_passed = False
    try:
        with tempfile.TemporaryDirectory(prefix="monitoring-guard-baseline-test-") as directory:
            BASELINE_PATH = Path(directory) / "monitoring-baseline.json"
            try:
                write_baseline(
                    Snapshot(
                        files={"monitoring/bad": object()},
                        compose={path: {"services": {}} for path in COMPOSE_PATHS},
                        services=[],
                    )
                )
            except GuardError as error:
                serialization_passed = "기준선 저장 실패" in str(error)
    finally:
        BASELINE_PATH = original_baseline_path
    total += 1
    if serialization_passed:
        print("PASS 회귀 | baseline 직렬화 불가 값 | GuardError")
        passed += 1
    else:
        print("FAIL 회귀 | baseline 직렬화 불가 값 | 비정상 예외/통과(오류)")

    type_state = copy.deepcopy(pristine_state)
    type_state.raw_compose[COMPOSE_PATHS[0]]["services"]["docker-socket-proxy"][
        "environment"
    ]["POST"] = False
    mark_compose_changed(type_state, COMPOSE_PATHS[0])
    type_evaluation = evaluate(baseline, snapshot_from_state(type_state))
    total += 1
    if not type_evaluation.passed:
        print("PASS 회귀 | 값 타입 변경 | 차단")
        passed += 1
    else:
        print("FAIL 회귀 | 값 타입 변경 | 통과(오류)")

    type_only_current = copy.deepcopy(baseline)
    type_only_current.compose[COMPOSE_PATHS[0]]["services"]["docker-socket-proxy"][
        "environment"
    ]["POST"] = False
    type_only_evaluation = evaluate(baseline, type_only_current)
    expected_type_path = (
        "compose",
        COMPOSE_PATHS[0],
        "services",
        "docker-socket-proxy",
        "environment",
        "POST",
    )
    expected_type_violation = f"{path_text(expected_type_path)} — 0 → false"
    total += 1
    if (
        type_only_current.files == baseline.files
        and type_only_evaluation.violations == [expected_type_violation]
    ):
        print("PASS 회귀 | 동일 파일 해시의 값 타입 변경 | 차이 보고")
        passed += 1
    else:
        print("FAIL 회귀 | 동일 파일 해시의 값 타입 변경 | 차이 미보고(오류)")

    original_baseline_path = BASELINE_PATH
    original_argv = sys.argv
    cli_fail_closed_passed = False
    try:
        with tempfile.TemporaryDirectory(
            prefix="monitoring-guard-cli-test-", dir=ROOT / "tools"
        ) as directory:
            BASELINE_PATH = Path(directory) / "missing-baseline.json"
            sys.argv = [original_argv[0]]
            cli_fail_closed_passed = main() == 1
    finally:
        BASELINE_PATH = original_baseline_path
        sys.argv = original_argv
    total += 1
    if cli_fail_closed_passed:
        print("PASS 회귀 | 공개 CLI baseline 부재 | rc=1")
        passed += 1
    else:
        print("FAIL 회귀 | 공개 CLI baseline 부재 | rc!=1(오류)")

    mutation_tree_count = 2 if workflow_passed else 1
    regression_count = total - len(SELF_TEST_NAMES) * mutation_tree_count
    print(
        f"self-test: {passed}/{total} PASS "
        f"({len(SELF_TEST_NAMES)}종 × {mutation_tree_count} 트리 + 회귀 {regression_count})"
    )
    return 0 if passed == total else 1


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument(
        "--update-baseline",
        action="store_true",
        help="현재 monitoring/ 트리로 기준선을 갱신한다",
    )
    mode.add_argument(
        "--self-test",
        action="store_true",
        help="메모리·임시 트리 변조로 회귀 방어를 검증한다",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        if args.update_baseline:
            write_baseline(snapshot_from_state(collect_memory_state()))
            return 0
        if args.self_test:
            return run_self_test()

        evaluation = evaluate(
            load_baseline(),
            snapshot_from_state(collect_memory_state()),
        )
        print_evaluation(evaluation)
        return 0 if evaluation.passed else 1
    except GuardError as error:
        print(f"monitoring-guard — 기준선/검사 가능 → 실패: {error}")
        print("monitoring-guard: 위반 1건")
        print("정당한 변경이면 --update-baseline으로 갱신하고 그 diff를 리뷰받으라.")
        return 1


if __name__ == "__main__":
    raise SystemExit(main())

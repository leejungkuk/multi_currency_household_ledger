#!/usr/bin/env python3
"""옛 버전과 공존하지 못할 수 있는 마이그레이션을 찾아 사람이 확인한 버전 목록과 대조한다.

두 색 배포에서는 매 배포마다 옛 버전이 새 스키마 위에서 수십 초 서비스한다(expand/contract 필수).
감지는 정규식 휴리스틱이라 놓치는 것도, 공존 가능한데 걸리는 것도 있다 — 최종 판단은 사람이고
이 스크립트는 그 판단을 승격 전에 강제로 요청하는 장치다.

사용: migration-ddl-guard.py [--expect VERSIONS] FILE... | migration-ddl-guard.py --self-test
종료 코드: 0 = 통과(또는 --expect 없이 보고만) · 1 = 감지 집합과 확인 입력 불일치 · 2 = 검사 불가
"""

from __future__ import annotations

import argparse
import contextlib
import io
import re
import tempfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
MIGRATION_DIR = ROOT / "module-api" / "src" / "main" / "resources" / "db" / "migration"
VERSION = re.compile(r"V(\d+)__")

# 문자열·인용 식별자는 그대로 둔다 — 그 안의 -- · /* · ; 는 주석도 문장 경계도 아니다.
TOKEN = re.compile(r"'(?:[^']|'')*'|\"(?:[^\"]|\"\")*\"|--[^\n]*|/\*.*?\*/|;", re.S)
LITERAL = re.compile(r"'(?:[^']|'')*'|\"(?:[^\"]|\"\")*\"")
CREATE_TABLE = re.compile(r"create table\b")
RULES = (
    ("drop", re.compile(r"\bdrop\b")),
    ("rename", re.compile(r"\brename\b")),
    # Postgres 는 ALTER [COLUMN] col [SET DATA] TYPE 이라 column 을 생략할 수 있다.
    ("타입 변경", re.compile(r"\balter (?:column )?\S+ (?:set data )?type\b")),
    ("set not null", re.compile(r"\bset not null\b")),
    # 표 제약 형태(add constraint · add check (...))만 본다. 새 컬럼에 붙은 컬럼 제약은 default 가 없을 때만 무관하다 —
    # 옛 버전의 insert 가 그 컬럼에 null 을 넣고 null 은 references·unique·check 를 통과한다. default 가 있으면 아래 따로 본다.
    ("제약 추가", re.compile(r"\balter table\b.*\badd (?:constraint|check|unique|primary key|foreign key)\b")),
    ("create unique index", re.compile(r"\bcreate unique index\b")),
    ("truncate", re.compile(r"\btruncate\b")),
    ("delete from", re.compile(r"\bdelete from\b")),
)
ADD = re.compile(r"\badd\b")
# primary key 는 not null 을 함께 건다.
ADD_NOT_NULL = re.compile(r"\badd\b.*\b(?:not null|primary key)\b")
# 옛 버전의 insert 에 default 가 들어가 FK 위반·두 번째 insert 의 중복·조건 밖 값으로 깨진다.
COLUMN_CONSTRAINT = re.compile(r"\b(?:references|unique|check|primary key)\b")
# on delete/update set default 는 FK 동작이지 컬럼의 default 가 아니다.
DEFAULT = re.compile(r"(?<!set )\bdefault\b")
EXPECTED_CHECKS = 44


class GuardError(RuntimeError):
    """검사가 실행되지 못한 상태를 감지 0건과 구분한다."""


def statements(sql: str) -> list[str]:
    """주석을 걷고 ; 로 나눠 소문자·공백 정규화한 문장들."""

    def replace(match: re.Match[str]) -> str:
        token = match.group()
        if token == ";":
            return "\0"
        return " " if token.startswith(("--", "/*")) else token

    parts = TOKEN.sub(replace, sql).split("\0")
    return [s for s in (" ".join(part.lower().split()) for part in parts) if s]


def clauses(statement: str) -> list[str]:
    """문자열·인용 식별자를 비우고 최상위 쉼표로 나눈다 — 'default' 같은 값이 절의 키워드로 읽히지 않고,
    numeric(19,2)·check (x in (...)) 같은 괄호 안 쉼표는 나누지 않는다."""
    statement = LITERAL.sub(lambda match: match.group()[0] * 2, statement)
    parts, depth, start = [], 0, 0
    for match in re.finditer(r"[(),]", statement):
        token = match.group()
        if token == "(":
            depth += 1
        elif token == ")":
            depth -= 1
        elif token == "," and depth == 0:
            parts.append(statement[start : match.start()])
            start = match.end()
    parts.append(statement[start:])
    return parts


def findings(sql: str) -> list[tuple[list[str], str]]:
    """걸린 문장마다 (범주들, 정규화된 문장). 새 테이블의 제약은 옛 버전과 무관해 create table 은 통째로 건너뛴다."""
    found = []
    for statement in statements(sql):
        if CREATE_TABLE.match(statement):
            continue
        hits = [name for name, rule in RULES if rule.search(statement)]
        # 한 문장에 add 가 여럿이면 default 는 그 절에 있어야 한다 — 문장 전체로 보면 다른 절의 default 가 가린다.
        adds = [c for c in clauses(statement) if ADD.search(c)]
        if any(ADD_NOT_NULL.search(c) and not DEFAULT.search(c) for c in adds):
            hits.append("default 없는 not null 추가")
        if any(DEFAULT.search(c) and COLUMN_CONSTRAINT.search(c) for c in adds):
            hits.append("default 있는 새 컬럼의 제약")
        if hits:
            found.append((hits, statement))
    return found


def version(path: Path) -> str:
    match = VERSION.match(path.name)
    if not match:
        raise GuardError(f"V<숫자>__ 로 시작하는 Flyway 파일명이 아니다: {path.name}")
    return f"V{int(match.group(1))}"


def parse_expect(text: str) -> set[str]:
    return {token.strip().upper() for token in text.split(",") if token.strip()}


def ordered(versions: set[str]) -> str:
    return ", ".join(sorted(versions, key=lambda v: (len(v), v))) or "(없음)"


def report(files: list[Path], expect: str | None) -> int:
    detected: dict[str, tuple[Path, list[tuple[list[str], str]]]] = {}
    for path in files:
        label = version(path)
        hits = findings(path.read_text(encoding="utf-8"))
        if hits:
            detected[label] = (path, hits)

    print("### 옛 버전과 공존하지 못할 수 있는 마이그레이션")
    print("")
    print(f"검사 파일 {len(files)}개 · 감지 {len(detected)}개 — 정규식 휴리스틱이다. 공존 가능 여부는 사람이 판단한다.")
    print("")
    for label in sorted(detected, key=lambda v: (len(v), v)):
        path, hits = detected[label]
        print(f"- **{label}** `{path.name}`")
        for categories, statement in hits:
            head = statement if len(statement) <= 100 else statement[:100] + "…"
            print(f"  - {' · '.join(categories)} — `{head}`")
    if expect is None:
        return 0

    acknowledged = parse_expect(expect)
    print("")
    print(f"- 감지: {ordered(set(detected))}")
    print(f"- 확인 입력: {ordered(acknowledged)}")
    if acknowledged == set(detected):
        print("- 결과: 일치")
        return 0
    print("- 결과: **불일치** — 감지된 버전이 옛 버전과 공존하는지 판단한 뒤 그 목록을 정확히 입력해 다시 실행한다")
    return 1


def run_self_test() -> int:
    passed = total = 0

    def check(name: str, ok: bool) -> None:
        nonlocal passed, total
        total += 1
        passed += ok
        print(f"{'PASS' if ok else 'FAIL'} | {name}")

    real = sorted(MIGRATION_DIR.glob("V1[3-8]__*.sql"))
    check("실제 V13~V18 파일 6개", len(real) == 6)
    detected = {version(p) for p in real if findings(p.read_text(encoding="utf-8"))}
    check(f"실제 V13~V18 감지 = V15·V16·V17 (실제 {ordered(detected)})", detected == {"V15", "V16", "V17"})

    cases = (
        ("주석 안의 drop 비감지", "-- drop table t;\nalter table t add column a text; /* drop\ncolumn a */", False),
        ("drop column 감지", "alter table t drop column a;", True),
        ("문자열 안의 -- 뒤 drop 감지", "insert into t values ('--'); drop table t;", True),
        ("rename 감지", "alter table t rename column a to b;", True),
        ("rename 이 든 이름 비감지", "alter table t add column renamed_at timestamp;", False),
        ("alter column a type bigint 감지", "alter table t alter column a type bigint;", True),
        ("alter column set data type 감지", "alter table t alter column a set data type bigint;", True),
        ("column 없는 alter a type 감지", "ALTER TABLE t ALTER a TYPE bigint;", True),
        ("set default 비감지", "alter table t alter column a set default 0;", False),
        ("set not null 감지", "alter table t alter column a set not null;", True),
        ("add column not null default 비감지", "alter table t add column x int not null default 0;", False),
        ("add column not null(default 없음) 감지", "alter table t add column x int not null;", True),
        ("다른 절의 default 가 가리지 않음", "alter table t add column x int not null default 0, add column y int not null;", True),
        ("문자열 안의 default 가 가리지 않음", "ALTER TABLE t ADD COLUMN state text NOT NULL CHECK (state <> 'default');", True),
        ("인용 식별자 \"default\" 가 가리지 않음", 'alter table t add column "default" int not null;', True),
        ("on delete set default 가 가리지 않음", "alter table t add column b bigint not null references u(id) on delete set default;", True),
        ("괄호 안 쉼표로 절이 갈리지 않음", "alter table t add column x int check (x in (1, 2)) not null;", True),
        ("add column primary key(default 없음) 감지", "alter table t add column id bigint primary key;", True),
        ("default 있는 새 컬럼의 references 감지", "alter table t add column x bigint not null default 0 references u(id);", True),
        ("default 있는 새 컬럼의 unique 감지", "alter table t add column x int default 0 unique;", True),
        ("default 있는 새 컬럼의 check 감지", "alter table t add column x int default 1 check (x > 5);", True),
        ("add constraint 감지", "alter table t add constraint ck check (a > 0);", True),
        ("add unique (...) 감지", "alter table t add unique (a);", True),
        ("add check (...) 감지", "alter table t add check (a > 0);", True),
        ("새 nullable 컬럼의 컬럼 check 비감지", "alter table t add column a numeric(19,2) check (a between 0 and 9);", False),
        ("create index 비감지", "create index idx_t_a on t (a);", False),
        ("create unique index 감지", "create unique index uk_t_a on t (a);", True),
        ("create table 안의 제약 비감지", "create table t (id bigint primary key, a int not null check (a > 0), unique (a));", False),
        ("create table 다음 문장의 drop 감지", "create table t (id bigint);\nalter table u drop column a;", True),
        ("truncate 감지", "truncate table t;", True),
        ("delete from 감지", "delete from t where id = 1;", True),
        ("on delete cascade FK 컬럼 비감지", "alter table t add column b bigint references u(id) on delete cascade;", False),
        ("on delete set default FK 컬럼(nullable) 비감지", "alter table t add column b bigint references u(id) on delete set default;", False),
        ("update … set 비감지", "update t set a = 1 where id = 2;", False),
    )
    for name, sql, expected in cases:
        check(name, bool(findings(sql)) is expected)

    def cli(*argv: str) -> int:
        with contextlib.redirect_stdout(io.StringIO()):
            return main(list(argv))

    v = {p.name[: p.name.index("__")]: str(p) for p in real}
    with tempfile.TemporaryDirectory(prefix="migration-ddl-guard-") as directory:
        odd = Path(directory) / "budget_drop.sql"
        odd.write_text("alter table t drop column a;", encoding="utf-8")
        cli_cases = (
            ("--expect 'V15, v16,V17' + 실제 6개 → 0", ("--expect", "V15, v16,V17", *v.values()), 0),
            ("--expect 'V15,V16' + 실제 6개(V17 누락) → 1", ("--expect", "V15,V16", *v.values()), 1),
            ("--expect 'V15,V16,V17,V18'(초과) → 1", ("--expect", "V15,V16,V17,V18", *v.values()), 1),
            ("--expect '' + V15 → 1", ("--expect", "", v["V15"]), 1),
            ("--expect '' + 파일 0개 → 0", ("--expect", ""), 0),
            ("--expect 없이 V15 → 보고만 0", (v["V15"],), 0),
            ("버전 없는 파일명 → 2", ("--expect", "", str(odd)), 2),
            ("없는 파일 → 2", ("--expect", "", str(Path(directory) / "V99__missing.sql")), 2),
        )
        for name, argv, expected in cli_cases:
            check(name, cli(*argv) == expected)

    ok = passed == total == EXPECTED_CHECKS
    print(f"self-test: {passed}/{total} PASS (기대 단언 수 {EXPECTED_CHECKS})")
    return 0 if ok else 1


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--expect", help="사람이 확인한 버전 쉼표 목록(예: V15,V16). 빈 문자열 = 없음")
    parser.add_argument("--self-test", action="store_true", help="실제 V13~V18 과 인라인 SQL 로 감지 규칙을 검증한다")
    parser.add_argument("files", nargs="*", type=Path)
    args = parser.parse_args(argv)
    if args.self_test:
        return run_self_test()
    try:
        return report(args.files, args.expect)
    except (GuardError, OSError, UnicodeDecodeError) as error:
        print(f"migration-ddl-guard: 검사 불가 — {error}")
        return 2


if __name__ == "__main__":
    raise SystemExit(main())

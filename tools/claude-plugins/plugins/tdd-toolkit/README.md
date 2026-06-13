# tdd-toolkit

환율 가계부 프로젝트의 TDD 보조 Claude Code 플러그인.

## 구성 요소

### 1. `write-unit-test` 스킬
프로젝트의 실제 테스트 컨벤션에 맞춰 단위/슬라이스 테스트를 작성한다.
- 계층(도메인/서비스/컨트롤러/리포지토리/외부 provider/스케줄러)별 셋업 자동 판별
- `@Nested` + 한글 `@DisplayName`, 영어 snake_case 메서드명, AssertJ `isEqualByComparingTo`
- `@DataJpaTest` + `@AutoConfigureTestDatabase(replace = Replace.NONE)`(실 MySQL), `@WebMvcTest`, WireMock 템플릿
- 모듈 test scaffolding(`Test<Module>Application`/`TestJpaConfig`)이 없으면 자동 생성
- 작성 후 `./gradlew test`로 검증

`skills/write-unit-test/SKILL.md`, `references/test-templates.md` 참고.

### 2. TDD reminder hook (`PostToolUse`, 비차단)
`module-*/src/main/java/.../Foo.java`를 편집했는데 대응 테스트 `.../src/test/java/.../FooTest.java`가 없으면 알림을 띄운다. **작업을 차단하지 않는다.**
- 제외 대상: `*Application.java`, `package-info.java`, `module-info.java`
- 동작/제외 규칙은 `hooks/tdd_reminder.py` 상단에서 조정 가능

## 설치

이 레포 자체가 로컬 마켓플레이스다. Claude Code에서:

```
/plugin marketplace add tools/claude-plugins
/plugin install tdd-toolkit@household-ledger-tools
```

설치 후 `write-unit-test` 스킬과 TDD hook이 활성화된다. 설치 확인:

```
/plugin
```

## 비활성화/제거

```
/plugin uninstall tdd-toolkit@household-ledger-tools
```

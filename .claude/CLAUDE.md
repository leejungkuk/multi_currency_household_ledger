# 프로젝트 명세: 환율 계산 가계부 (Project exchange_rate_calculator)

## 🚀 실행 및 빌드 명령어
- 빌드: `./gradlew build`
- 테스트 실행: `./gradlew test`
- 특정 테스트 실행: `./gradlew test --tests <클래스명>`
- 로컬 서버 실행: `./gradlew bootRun`

## 🛠 기술 스택 가이드라인
- **Framework:** Spring Boot 3.x, Java 21
- **Database:** MySQL 8.0
- **Persistence:** Spring Data JPA (QueryDSL 사용 지양, 필요시 전용 Repository 작성)
- **Concurrency:** 티켓 잔여 수량 등 정합성이 중요한 로직은 반드시 분산 락(Distributed Lock)을 적용할 것.

## 📏 코딩 표준 (Code Standards)
- **Lombok:** `@RequiredArgsConstructor`를 사용하여 생성자 주입을 사용할 것.
- **DTO:** API 응답은 반드시 전용 DTO를 사용하며, Entity를 직접 노출하지 말 것.
- **JPA:** `@ManyToOne` 관계는 항상 `FetchType.LAZY`를 설정하여 N+1 문제를 방지할 것.
- **Logging:** `Slf4j`를 사용하며, 비즈니스 에러 발생 시 상세 컨텍스트를 로그에 남길 것.

## 🧪 테스트 및 품질 (QA)
- TDD 원칙에 따라 실패하는 테스트 코드를 먼저 작성하고 기능을 구현할 것.
- `@DataJpaTest` 사용 시 롤백 정책을 준수할 것.

## Skill routing

When the user's request matches an available skill, ALWAYS invoke it using the Skill
tool as your FIRST action. Do NOT answer directly, do NOT use other tools first.
The skill has specialized workflows that produce better results than ad-hoc answers.

Key routing rules:
- Product ideas, "is this worth building", brainstorming → invoke office-hours
- Bugs, errors, "why is this broken", 500 errors → invoke investigate
- Ship, deploy, push, create PR → invoke ship
- QA, test the site, find bugs → invoke qa
- Code review, check my diff → invoke review
- Update docs after shipping → invoke document-release
- Weekly retro → invoke retro
- Design system, brand → invoke design-consultation
- Visual audit, design polish → invoke design-review
- Architecture review → invoke plan-eng-review
- Save progress, checkpoint, resume → invoke checkpoint
- Code quality, health check → invoke health
package com.self.multi_currency_household_ledger.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * CLAUDE.md 아키텍처 규칙을 빌드 게이트로 강제한다.
 *
 * <p>module-api 는 전 모듈을 의존하므로 여기서 전체 클래스패스를 검사한다. module-api 방향(도메인
 * 모듈→api 금지)은 Gradle 모듈 그래프가 이미 컴파일 수준에서 막으므로 규칙에서 제외한다.
 */
@AnalyzeClasses(
        packages = "com.self.multi_currency_household_ledger",
        importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    // ── 모듈 의존 방향: 항상 안쪽(common)을 향한다 ──────────────────────────────

    @ArchTest
    static final ArchRule common_should_not_depend_on_domain_modules = noClasses()
            .that()
            .resideInAPackage("..common..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("..exchange..", "..ledger..", "..member..");

    @ArchTest
    static final ArchRule exchange_should_not_depend_on_other_domains = noClasses()
            .that()
            .resideInAPackage("..exchange..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("..ledger..", "..member..");

    @ArchTest
    static final ArchRule ledger_should_not_depend_on_member = noClasses()
            .that()
            .resideInAPackage("..ledger..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("..member..");

    @ArchTest
    static final ArchRule member_should_not_depend_on_other_domains = noClasses()
            .that()
            .resideInAPackage("..member..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("..exchange..", "..ledger..")
            // module-member 는 아직 빈 스캐폴드 — 클래스 0개여도 규칙 자체는 유지한다
            .allowEmptyShould(true);

    // ── 계층 규칙: Rich Domain — domain 은 상위 계층을 모른다 ───────────────────

    @ArchTest
    static final ArchRule domain_should_not_depend_on_upper_layers = noClasses()
            .that()
            .resideInAPackage("..domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("..service..", "..controller..", "..dto..", "..scheduler..", "..provider..");

    @ArchTest
    static final ArchRule controllers_should_not_use_repositories_directly = noClasses()
            .that()
            .resideInAPackage("..controller..")
            .should()
            .dependOnClassesThat()
            .haveSimpleNameEndingWith("Repository");

    @ArchTest
    static final ArchRule dto_should_not_depend_on_upper_layers = noClasses()
            .that()
            .resideInAPackage("..dto..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("..service..", "..controller..", "..scheduler..");
}

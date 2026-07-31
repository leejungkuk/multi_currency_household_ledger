package com.self.multi_currency_household_ledger.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.HasDescription;
import com.tngtech.archunit.core.domain.properties.HasAnnotations;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Profiles;
import org.springframework.mock.env.MockEnvironment;

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

    // ── local 전용 dev 빈은 배포 프로파일에서 반드시 배제된다 ──────────────────────

    /**
     * {@code local} 에서 활성인 빈은 {@code prod} 가 함께 켜지면 배제돼야 한다. 두 프로파일은 배타적이지 않아
     * {@code SPRING_PROFILES_ACTIVE=prod,local} 하나로 dev 편의 빈이 운영에서 살아난다 — Swagger 개방,
     * 무인증 체인, 인증만 있으면 누구나 부를 수 있는 수집 트리거가 그 예다.
     *
     * <p>대상을 열거하지 않고 스캔하는 것이 요점이다: 이 규칙이 막으려는 실수가 정확히 "dev 빈을
     * {@code @Profile("local")} 로 새로 추가하는 것"이라, 열거식 검사는 그 재발을 잡지 못한다.
     */
    @ArchTest
    static final ArchRule local_only_classes_should_be_excluded_when_prod_is_active =
            classes().that().areAnnotatedWith(Profile.class).should(excludedWhenProdIsActive());

    /** {@code @Profile} 은 {@code @Bean} 메서드에도 붙는다 — dev 편의 빈을 추가하는 가장 흔한 형태라 클래스만 봐선 부족하다. */
    @ArchTest
    static final ArchRule local_only_bean_methods_should_be_excluded_when_prod_is_active = methods()
            .that()
            .areAnnotatedWith(Profile.class)
            .should(excludedWhenProdIsActive())
            .allowEmptyShould(true);

    private static <T extends HasAnnotations<?> & HasDescription> ArchCondition<T> excludedWhenProdIsActive() {
        return new ArchCondition<>("prod 동시 활성 시 배제되는 프로파일 표현식을 쓴다") {
            @Override
            public void check(T item, ConditionEvents events) {
                Profiles profiles =
                        Profiles.of(item.getAnnotationOfType(Profile.class).value());
                if (!accepts(profiles, "local")) {
                    return; // local 전용 빈이 아니면 이 규칙의 대상이 아니다
                }
                if (accepts(profiles, "prod", "local")) {
                    events.add(SimpleConditionEvent.violated(
                            item,
                            item.getDescription()
                                    + " 은 local 활성 빈인데 prod 동시 활성에서도 등록된다 — @Profile(\"local & !prod\") 처럼 좁혀야 한다"));
                }
            }

            private boolean accepts(Profiles profiles, String... activeProfiles) {
                MockEnvironment environment = new MockEnvironment();
                environment.setActiveProfiles(activeProfiles);
                return environment.acceptsProfiles(profiles);
            }
        };
    }
}

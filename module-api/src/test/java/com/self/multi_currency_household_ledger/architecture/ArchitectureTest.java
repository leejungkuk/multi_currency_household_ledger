package com.self.multi_currency_household_ledger.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import archfixture.BoundUuidControllerFixture;
import archfixture.InheritedUnboundUuidControllerFixture;
import archfixture.UnboundUuidControllerFixture;
import com.self.multi_currency_household_ledger.common.annotation.CurrentMemberId;
import com.tngtech.archunit.base.HasDescription;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaParameter;
import com.tngtech.archunit.core.domain.properties.HasAnnotations;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Profiles;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * CLAUDE.md 아키텍처 규칙을 빌드 게이트로 강제한다.
 *
 * <p>module-api 는 전 모듈을 의존하므로 여기서 전체 클래스패스를 검사한다. module-api 방향(도메인
 * 모듈→api 금지)은 Gradle 모듈 그래프가 이미 컴파일 수준에서 막으므로 규칙에서 제외한다.
 *
 * <p>{@code @AnalyzeClasses}/{@code @ArchTest} 대신 core ArchUnit 을 직접 돌린다: {@code archunit-junit5}
 * 의 TestEngine 은 JUnit Platform 6 에서 로드되지 않고, 엔진 미로드는 실패가 아니라 "규칙 0건 실행"으로
 * 조용히 통과한다. {@code archunit-junit6} 가 나오면 되돌릴 수 있도록 규칙 필드는 그대로 둔다.
 */
class ArchitectureTest {

    /** 규칙 필드 수. 리플렉션 열거가 규칙을 빠뜨리거나 규칙이 삭제되면 즉시 깨지도록 못박는다. */
    private static final int RULE_COUNT = 10;

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("com.self.multi_currency_household_ledger");

    // ── 모듈 의존 방향: 항상 안쪽(common)을 향한다 ──────────────────────────────

    static final ArchRule common_should_not_depend_on_domain_modules = noClasses()
            .that()
            .resideInAPackage("..common..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("..exchange..", "..ledger..", "..member..");

    static final ArchRule exchange_should_not_depend_on_other_domains = noClasses()
            .that()
            .resideInAPackage("..exchange..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("..ledger..", "..member..");

    static final ArchRule ledger_should_not_depend_on_member = noClasses()
            .that()
            .resideInAPackage("..ledger..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("..member..");

    static final ArchRule member_should_not_depend_on_other_domains = noClasses()
            .that()
            .resideInAPackage("..member..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("..exchange..", "..ledger..")
            // 패키지 필터가 리팩터링 중 일시적으로 비어도 의존 방향 규칙 자체는 유지한다
            .allowEmptyShould(true);

    // ── 계층 규칙: Rich Domain — domain 은 상위 계층을 모른다 ───────────────────

    static final ArchRule domain_should_not_depend_on_upper_layers = noClasses()
            .that()
            .resideInAPackage("..domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("..service..", "..controller..", "..dto..", "..scheduler..", "..provider..");

    static final ArchRule controllers_should_not_use_repositories_directly = noClasses()
            .that()
            .resideInAPackage("..controller..")
            .should()
            .dependOnClassesThat()
            .haveSimpleNameEndingWith("Repository");

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
    static final ArchRule local_only_classes_should_be_excluded_when_prod_is_active =
            classes().that().areAnnotatedWith(Profile.class).should(excludedWhenProdIsActive());

    /** {@code @Profile} 은 {@code @Bean} 메서드에도 붙는다 — dev 편의 빈을 추가하는 가장 흔한 형태라 클래스만 봐선 부족하다. */
    static final ArchRule local_only_bean_methods_should_be_excluded_when_prod_is_active = methods()
            .that()
            .areAnnotatedWith(Profile.class)
            .should(excludedWhenProdIsActive())
            .allowEmptyShould(true);

    // ── 컨트롤러의 UUID 파라미터는 반드시 명시적으로 바인딩한다 ─────────────────

    /**
     * 요청 매핑 메서드의 {@code UUID} 파라미터는 {@code @CurrentMemberId}·{@code @PathVariable}·{@code @RequestParam}·
     * {@code @RequestHeader} 중 하나로 바인딩돼야 한다.
     *
     * <p>막으려는 실수는 하나다: {@code @CurrentMemberId} 를 빠뜨린 {@code UUID memberId}. Spring MVC 는 어노테이션이
     * 없는 단순 타입 파라미터를 <b>쿼리스트링에서 바인딩</b>하므로, 그 한 글자 누락이 곧 "요청자가 회원 id 를 스스로
     * 고르는" 즉시 IDOR 이다.
     *
     * <p>클래스 어노테이션에 관계없이 {@code @RequestMapping} 메타 어노테이션이 붙은 메서드를 검사하므로,
     * 부모 클래스·인터페이스에 선언된 매핑도 검사한다. 매핑 없는 보조 메서드는 대상에서 제외한다.
     * {@code Optional<UUID>}·{@code UUID[]}·{@code UUID} 필드를 가진 복합 DTO 파라미터(model attribute)는
     * 검사하지 못한다.
     */
    static final ArchRule controller_uuid_parameters_must_be_explicitly_bound = methods()
            .that()
            .areMetaAnnotatedWith(RequestMapping.class)
            .should(haveExplicitlyBoundUuidParameters())
            .allowEmptyShould(true);

    /**
     * 위 {@code ArchRule} 필드를 리플렉션으로 전부 열거해 실행한다. 수동 나열은 규칙 하나를 빠뜨려도
     * 아무것도 실패하지 않으므로 쓰지 않는다.
     */
    @TestFactory
    Stream<DynamicTest> architecture_rules() {
        List<Field> ruleFields = Arrays.stream(ArchitectureTest.class.getDeclaredFields())
                .filter(field -> ArchRule.class.isAssignableFrom(field.getType()))
                .toList();
        // 빈 스트림은 JUnit 이 성공으로 처리하므로, 개수 단언은 동적 테스트 밖에서 즉시 실행한다.
        assertThat(ruleFields).hasSize(RULE_COUNT);
        return ruleFields.stream()
                .map(field -> DynamicTest.dynamicTest(
                        field.getName(), () -> ruleOf(field).check(CLASSES)));
    }

    private static ArchRule ruleOf(Field field) {
        try {
            return (ArchRule) field.get(null);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("규칙 필드를 읽지 못했다: " + field.getName(), e);
        }
    }

    /**
     * 규칙이 조용히 죽는 것을 막는다 — 실 컨트롤러는 전부 정상이라 규칙을 무력화해도 {@code @TestFactory} 는 그린이다.
     * 픽스처는 베이스 패키지 밖에 있어 {@link #CLASSES} 에도, 컴포넌트 스캔에도 잡히지 않으므로 여기서 직접 임포트한다.
     */
    @Test
    @DisplayName("어노테이션 없는 UUID 파라미터를 가진 매핑 메서드는 위반으로 잡힌다")
    void unbound_uuid_parameter_is_reported_as_violation() {
        JavaClasses fixture = new ClassFileImporter().importClasses(UnboundUuidControllerFixture.class);

        assertThat(controller_uuid_parameters_must_be_explicitly_bound
                        .evaluate(fixture)
                        .hasViolation())
                .isTrue();
    }

    @Test
    @DisplayName("컨트롤러가 아닌 부모에서 상속한 매핑의 미바인딩 UUID 파라미터는 위반으로 잡힌다")
    void inherited_unbound_uuid_parameter_is_reported_as_violation() {
        JavaClasses fixture = new ClassFileImporter()
                .importClasses(
                        InheritedUnboundUuidControllerFixture.Parent.class,
                        InheritedUnboundUuidControllerFixture.Child.class);

        assertThat(controller_uuid_parameters_must_be_explicitly_bound
                        .evaluate(fixture)
                        .hasViolation())
                .isTrue();
    }

    @Test
    @DisplayName("네 바인딩 어노테이션과 매핑 없는 보조 메서드는 위반이 아니다")
    void bound_uuid_parameters_are_not_reported() {
        JavaClasses fixture = new ClassFileImporter().importClasses(BoundUuidControllerFixture.class);

        assertThat(controller_uuid_parameters_must_be_explicitly_bound
                        .evaluate(fixture)
                        .hasViolation())
                .isFalse();
    }

    private static ArchCondition<JavaMethod> haveExplicitlyBoundUuidParameters() {
        List<Class<? extends Annotation>> allowed =
                List.of(CurrentMemberId.class, PathVariable.class, RequestParam.class, RequestHeader.class);
        return new ArchCondition<>(
                "UUID 파라미터를 " + allowed.stream().map(Class::getSimpleName).toList() + " 중 하나로 바인딩한다") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                for (JavaParameter parameter : method.getParameters()) {
                    if (!parameter.getRawType().isEquivalentTo(UUID.class)
                            || allowed.stream().anyMatch(parameter::isAnnotatedWith)) {
                        continue;
                    }
                    events.add(SimpleConditionEvent.violated(
                            method,
                            method.getFullName() + " 의 " + parameter.getIndex()
                                    + "번 UUID 파라미터에 바인딩 어노테이션이 없다 — 쿼리스트링에서 그대로 바인딩돼 IDOR 이 된다"));
                }
            }
        };
    }

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

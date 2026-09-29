package com.example.urlshortener.app;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * The module boundaries, asserted rather than documented.
 *
 * <p>Every claim this project makes about separation — two planes that do not know about each
 * other, a domain free of framework coupling, controllers that go through services — is only worth
 * as much as the thing that stops it eroding. These rules are that thing; they fail the build, not
 * a review comment six months later.
 */
class ArchitectureBoundaryTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.example.urlshortener");
    }

    @Test
    void the_application_plane_does_not_depend_on_the_control_plane() {
        ArchRule rule = noClasses()
                .that().resideInAnyPackage("com.example.urlshortener.domain..",
                        "com.example.urlshortener.infrastructure..",
                        "com.example.urlshortener.api..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("com.example.urlshortener.orchestration..",
                        "com.example.urlshortener.policy..")
                .because("the application plane must remain independent of SDLC orchestration");

        rule.check(classes);
    }

    @Test
    void the_control_plane_does_not_depend_on_the_application_plane() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.example.urlshortener.orchestration..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("com.example.urlshortener.domain..",
                        "com.example.urlshortener.api..",
                        "com.example.urlshortener.infrastructure..")
                .because("the control plane orchestrates the SDLC, not the shortener's data");

        rule.check(classes);
    }

    @Test
    void the_domain_is_free_of_spring_and_jpa() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.example.urlshortener.domain..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("org.springframework..", "jakarta.persistence..", "org.hibernate..")
                .because("domain rules must be testable without a container and portable across adapters");

        rule.check(classes);
    }

    @Test
    void controllers_do_not_reach_past_services_into_repositories() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.example.urlshortener.api.web..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("com.example.urlshortener.infrastructure.jpa..",
                        "com.example.urlshortener.infrastructure.entity..")
                .because("controllers map HTTP to use cases; persistence sits behind the application services");

        rule.check(classes);
    }

    @Test
    void the_policy_engine_does_not_depend_on_the_orchestration_engine() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.example.urlshortener.policy..")
                .should().dependOnClassesThat()
                .resideInAPackage("com.example.urlshortener.orchestration..")
                .because("policy rules are evaluated by the engine, not the other way round");

        rule.check(classes);
    }

    @Test
    void telemetry_depends_on_nothing_else_in_the_system() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.example.urlshortener.telemetry..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("com.example.urlshortener.orchestration..",
                        "com.example.urlshortener.api..",
                        "com.example.urlshortener.domain..",
                        "com.example.urlshortener.infrastructure..",
                        "com.example.urlshortener.policy..")
                .because("both planes report into telemetry, so telemetry must not know about either");

        rule.check(classes);
    }

    @Test
    void every_stage_agent_lives_in_the_agents_module() {
        ArchRule rule = classes()
                .that().implement("com.example.urlshortener.orchestration.engine.StageAgent")
                .should().resideInAPackage("com.example.urlshortener.orchestration.agents..")
                .because("the set of things the engine will execute has to be findable in one place");

        rule.check(classes);
    }
}

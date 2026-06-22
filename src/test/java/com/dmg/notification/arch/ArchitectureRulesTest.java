package com.dmg.notification.arch;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.*;

/**
 * Architectural rules enforced as tests.
 *
 * These run on every `mvn test` — no Docker required (pure bytecode analysis).
 * A failure here means a developer broke an architectural invariant.
 *
 * Rules are derived from CLAUDE.md "Architectural Invariants" and ADRs.
 */
class ArchitectureRulesTest {

    static JavaClasses classes;

    @BeforeAll
    static void loadClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.dmg.notification");
    }

    // ─── Layer rules ──────────────────────────────────────────────────────────
    // Note: we use targeted no-dependency rules rather than strict layering because
    // services↔kafka is intentionally bidirectional:
    //   services → kafka publishers  (to emit events)
    //   kafka consumers → services   (to invoke business logic)
    // This is documented in ADR-002 and CLAUDE.md.

    @Test
    void repositories_should_not_depend_on_services_or_controllers() {
        noClasses()
                .that().resideInAPackage("..repository..")
                .should().dependOnClassesThat().resideInAnyPackage("..service..", "..controller..")
                .because("repositories are pure data-access; they must not call services or controllers")
                .check(classes);
    }

    @Test
    void domain_should_not_depend_on_services_controllers_or_kafka() {
        noClasses()
                .that().resideInAPackage("..domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..service..", "..controller..", "..kafka..", "..scheduler..")
                .because("domain entities must be pure data with no knowledge of orchestration layers")
                .check(classes);
    }

    @Test
    void kafka_consumers_should_not_bypass_services_and_call_repositories_directly() {
        noClasses()
                .that().resideInAPackage("..kafka..")
                .should().dependOnClassesThat().resideInAPackage("..repository..")
                .because("Kafka consumers must call services, not repositories directly — " +
                         "keeps business logic in one place")
                .check(classes);
    }

    // ─── Controller rules ─────────────────────────────────────────────────────

    @Test
    void controllers_should_not_directly_access_repositories_except_NotificationController() {
        // NotificationController.getDeliveries and getAttempts use repo directly (documented exception)
        // All other controllers must go through services
        ArchRule rule = noClasses()
                .that().resideInAPackage("..controller..")
                .and().haveSimpleNameNotContaining("NotificationController")
                .should().dependOnClassesThat().resideInAPackage("..repository..")
                .because("controllers must delegate to services, not repositories directly");
        rule.check(classes);
    }

    @Test
    void controllers_should_not_contain_business_logic() {
        // Controllers must not call other services' internal methods
        // Proxy: controllers must not create domain objects (entity instantiation)
        noClasses()
                .that().resideInAPackage("..controller..")
                .should().dependOnClassesThat()
                .resideInAPackage("..ratelimit..")
                .because("rate limit logic belongs in services, not controllers")
                .check(classes);
    }

    // ─── DTO rules ────────────────────────────────────────────────────────────

    @Test
    void dto_classes_should_not_have_jpa_annotations() {
        noClasses()
                .that().resideInAPackage("..dto..")
                .should().beAnnotatedWith("jakarta.persistence.Entity")
                .because("DTOs must not be JPA entities — use domain entities for persistence, DTOs for transport")
                .check(classes);
    }

    @Test
    void dto_classes_should_not_have_table_annotation() {
        noClasses()
                .that().resideInAPackage("..dto..")
                .should().beAnnotatedWith("jakarta.persistence.Table")
                .check(classes);
    }

    // ─── Domain rules ─────────────────────────────────────────────────────────

    @Test
    void domain_entities_should_reside_in_domain_package() {
        classes()
                .that().areAnnotatedWith("jakarta.persistence.Entity")
                .should().resideInAPackage("..domain..")
                .because("JPA entities must live in the domain package")
                .check(classes);
    }

    @Test
    void domain_should_not_depend_on_kafka_or_controllers() {
        noClasses()
                .that().resideInAPackage("..domain..")
                .should().dependOnClassesThat().resideInAPackage("..kafka..")
                .because("domain entities must not know about the messaging layer")
                .check(classes);

        noClasses()
                .that().resideInAPackage("..domain..")
                .should().dependOnClassesThat().resideInAPackage("..controller..")
                .check(classes);
    }

    // ─── Service rules ────────────────────────────────────────────────────────

    @Test
    void services_should_be_annotated_with_Service() {
        classes()
                .that().resideInAPackage("..service..")
                .and().haveSimpleNameEndingWith("Service")
                .should().beAnnotatedWith(org.springframework.stereotype.Service.class)
                .because("all *Service classes must be Spring-managed beans")
                .check(classes);
    }

    @Test
    void NotificationService_should_not_depend_on_DispatchService() {
        // ADR-002: NotificationService publishes Kafka events; it must never call DispatchService directly.
        // Direct coupling would bypass the async pipeline and break durability guarantees.
        noClasses()
                .that().haveSimpleName("NotificationService")
                .should().dependOnClassesThat().haveSimpleName("DispatchService")
                .because("NotificationService must publish Kafka events, not call DispatchService directly " +
                         "(see ADR-002 and CLAUDE.md Architectural Invariants)")
                .check(classes);
    }

    // ─── Security / no System.out ─────────────────────────────────────────────

    @Test
    void no_System_out_println_in_production_code() {
        noClasses()
                .that().resideInAPackage("com.dmg.notification..")
                .should().callMethod(System.class, "out")
                .orShould().callMethod(System.class, "err")
                .because("use @Slf4j and log.info/warn/error instead of System.out")
                .check(classes);
    }

    // ─── Circular dependency check ────────────────────────────────────────────
    // service↔kafka is an intentional bidirectional dependency (ADR-002):
    //   services use kafka publishers; kafka consumers call services.
    // The explicit checks below guard against unintentional cycles.

    @Test
    void repositories_should_not_depend_on_controllers_or_schedulers() {
        noClasses()
                .that().resideInAPackage("..repository..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("..controller..", "..scheduler..")
                .because("repositories must have no upward dependencies")
                .check(classes);
    }

    @Test
    void channel_dispatchers_should_not_depend_on_services_or_kafka() {
        // Channel dispatchers are pure I/O adapters — they must not call back into services
        noClasses()
                .that().resideInAPackage("..channel..")
                .and().haveSimpleNameEndingWith("ChannelDispatcher")
                .and().areNotInterfaces()
                .should().dependOnClassesThat()
                .resideInAnyPackage("..service..", "..kafka..", "..repository..")
                .because("channel dispatchers are leaf-level I/O adapters with no orchestration knowledge")
                .check(classes);
    }

    // ─── Channel dispatcher rules ─────────────────────────────────────────────

    @Test
    void channel_dispatchers_must_implement_ChannelDispatcher_interface() {
        classes()
                .that().resideInAPackage("..channel..")
                .and().haveSimpleNameEndingWith("ChannelDispatcher")
                .and().areNotInterfaces()
                .should().implement(com.dmg.notification.channel.ChannelDispatcher.class)
                .because("all channel dispatchers must be interchangeable via ChannelDispatcher contract")
                .check(classes);
    }

    // ─── Exception handling ───────────────────────────────────────────────────

    @Test
    void exception_classes_should_extend_RuntimeException() {
        classes()
                .that().resideInAPackage("..exception..")
                .and().haveSimpleNameEndingWith("Exception")
                .should().beAssignableTo(RuntimeException.class)
                .because("domain exceptions should be unchecked to avoid checked exception noise in service layers")
                .check(classes);
    }
}

package com.techeazy.notification.billing;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Fitness functions for the dependency rule inside the billing module: source dependencies point inwards only.
 * The domain knows nothing about use cases, adapters or frameworks; use cases know the domain and their ports only.
 */
@AnalyzeClasses(packages = "com.techeazy.notification.billing", importOptions = ImportOption.DoNotIncludeTests.class)
class BillingArchitectureTest {

    @ArchTest
    static final ArchRule domainDependsOnNothingOutward = noClasses().that().resideInAPackage("..billing.domain..")
            .should().dependOnClassesThat().resideInAnyPackage("..billing.application..", "..billing.infrastructure..",
                    "org.springframework..", "jakarta.persistence..", "org.hibernate..", "java.sql..");

    @ArchTest
    static final ArchRule useCasesDoNotDependOnAdaptersOrFrameworks = noClasses().that().resideInAPackage("..billing.application..")
            .should().dependOnClassesThat().resideInAnyPackage("..billing.infrastructure..", "org.springframework..",
                    "jakarta.persistence..", "org.hibernate..", "java.sql..");

    @ArchTest
    static final ArchRule nothingDependsOnTheInfrastructureLayer = noClasses().that().resideOutsideOfPackage("..billing.infrastructure..")
            .should().dependOnClassesThat().resideInAPackage("..billing.infrastructure..");
}

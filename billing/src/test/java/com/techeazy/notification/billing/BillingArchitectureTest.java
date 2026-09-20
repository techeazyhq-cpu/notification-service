/*
 * Copyright 2026 Vasantha Kumar
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * @author Vasantha Kumar <vasantha.kumar@hotmail.com>
 */

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

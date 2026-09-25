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

package com.techeazy.notification;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Fitness functions for {@code notification-core}, scoped to what already holds today rather than the target shape.
 *
 * <p>Unlike {@code billing}, this module's domain classes are JPA entities (see the architecture review, finding
 * A1) and its application classes call Spring Data repositories directly instead of going through ports; both are
 * tracked, deliberate debt, not something these rules can forbid without breaking the build. What these rules do
 * lock in: the domain package stays free of this module's other layers and of Spring's web/data annotations, and
 * the port package stays a pure abstraction with no dependency on an adapter. Tightening these rules further is the
 * natural next step of the A1 migration, not a reason to leave the module with no fitness function at all.
 */
@AnalyzeClasses(packages = "com.techeazy.notification", importOptions = ImportOption.DoNotIncludeTests.class)
class NotificationCoreArchitectureTest {

    private static final String BASE = "com.techeazy.notification";

    @ArchTest
    static final ArchRule domainDoesNotDependOnOtherLayers = noClasses().that().resideInAPackage(BASE + ".domain..")
            .should().dependOnClassesThat().resideInAnyPackage(BASE + ".application..", BASE + ".infra..", BASE + ".persistence..", BASE + ".config..", BASE + ".port..");

    @ArchTest
    static final ArchRule domainDoesNotDependOnSpringWebOrData = noClasses().that().resideInAPackage(BASE + ".domain..")
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework.web..", "org.springframework.data..", "org.springframework.stereotype..");

    @ArchTest
    static final ArchRule portsDoNotDependOnAdapters = noClasses().that().resideInAPackage(BASE + ".port..")
            .should().dependOnClassesThat().resideInAnyPackage(BASE + ".infra..", BASE + ".persistence..", BASE + ".application..", BASE + ".config..");
}

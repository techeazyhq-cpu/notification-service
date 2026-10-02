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
package com.techeazy.notification.dispatcher;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertyResolver;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The dispatcher has no public surface; its only port serves health and metrics (see ADR-021). Read from {@code application.yml} with only its own defaults, so a variable set on the build machine cannot mask it.
 */
class OperationalEndpointsConfigurationTest {

    private static PropertyResolver shippedConfiguration;

    @BeforeAll
    static void loadShippedConfiguration() throws IOException {
        MutablePropertySources sources = new MutablePropertySources();
        new YamlPropertySourceLoader().load("application.yml", new ClassPathResource("application.yml"))
                .forEach(sources::addLast);
        shippedConfiguration = new PropertySourcesPropertyResolver(sources);
    }

    /** Kubernetes probes {@code /actuator/health/liveness} and {@code /readiness} on the management port. */
    @Test
    void livenessAndReadinessProbesAreServed() {
        assertThat(shippedConfiguration.getProperty("management.endpoint.health.probes.enabled", Boolean.class))
                .isTrue();
    }

    @Test
    void onlyHealthInformationAndMetricsAreExposed() {
        assertThat(shippedConfiguration.getProperty("management.endpoints.web.exposure.include"))
                .isEqualTo("health,info,prometheus,providerhealth");
    }
}

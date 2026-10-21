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
package com.techeazy.notification.clientapi;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertyResolver;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Redis database index is configurable, so the service can share one Redis server with other applications
 * (ADR-040): index 0 when it runs alone, {@code REDIS_DATABASE} when it shares.
 */
class RedisDatabaseConfigurationTest {

    @Test
    void usesDatabaseZeroByDefault() throws IOException {
        assertThat(shippedConfigurationWith(Map.of()).getProperty("spring.data.redis.database", Integer.class))
                .isZero();
    }

    @Test
    void usesTheDatabaseThatRedisDatabaseNames() throws IOException {
        assertThat(shippedConfigurationWith(Map.of("REDIS_DATABASE", "2"))
                .getProperty("spring.data.redis.database", Integer.class)).isEqualTo(2);
    }

    private static PropertyResolver shippedConfigurationWith(Map<String, Object> environment) throws IOException {
        MutablePropertySources sources = new MutablePropertySources();
        sources.addFirst(new MapPropertySource("environment", environment));
        new YamlPropertySourceLoader().load("application.yml", new ClassPathResource("application.yml"))
                .forEach(sources::addLast);
        return new PropertySourcesPropertyResolver(sources);
    }
}
